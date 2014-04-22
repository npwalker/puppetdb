;; ## SQL query compiler
;;
;; The query compile operates in a multi-step process. Compilation begins with
;; one of the `foo-query->sql` functions. The job of these functions is
;; basically to call `compile-term` on the first term of the query to get back
;; the "compiled" form of the query, and then to turn that into a complete SQL
;; query.
;;
;; The compiled form of a query consists of a map with two keys: `where`
;; and `params`. The `where` key contains SQL for querying that
;; particular predicate, written in such a way as to be suitable for placement
;; after a `WHERE` clause in the database. `params` contains, naturally, the
;; parameters associated with that SQL expression. For instance, a resource
;; query for `["=" ["node" "name"] "foo.example.com"]` will compile to:
;;
;;     {:where "catalogs.certname = ?"
;;      :params ["foo.example.com"]}
;;
;; The `where` key is then inserted into a template query to return
;; the final result as a string of SQL code.
;;
;; The compiled query components can be combined by operators such as
;; `AND` or `OR`, which return the same sort of structure. Operators
;; which accept other terms as their arguments are responsible for
;; compiling their arguments themselves. To facilitate this, those
;; functions accept as their first argument a map from operator to
;; compile function. This allows us to have a different set of
;; operators for resources and facts, or queries, while still sharing
;; the implementation of the operators themselves.
;;
;; Other operators include the subquery operators, `in`, `extract`, and
;; `select-resources` or `select-facts`. The `select-foo` operators implement
;; subqueries, and are simply implemented by calling their corresponding
;; `foo-query->sql` function, which means they return a complete SQL query
;; rather than the compiled query map. The `extract` function knows how to
;; handle that, and is the only place those queries are allowed as arguments.
;; `extract` is used to select a particular column from the subquery. The
;; sibling operator to `extract` is `in`, which checks that the value of
;; a certain column from the table being queried is in the result set returned
;; by `extract`. Composed, these three operators provide a complete subquery
;; facility. For example, consider this fact query:
;;
;;     ["and"
;;      ["=" ["fact" "name"] "ipaddress"]
;;      ["in" "certname"
;;       ["extract" "certname"
;;        ["select-resources" ["and"
;;                             ["=" "type" "Class"]
;;                             ["=" "title" "apache"]]]]]]
;;
;; This will perform a query (via `select-resources`) for resources matching
;; `Class[apache]`. It will then pick out the `certname` from each of those,
;; and match against the `certname` of fact rows, returning those facts which
;; have a corresponding entry in the results of `select-resources` and which
;; are named `ipaddress`. Effectively, the semantics of this query are "find
;; the ipaddress of every node with Class[apache]".
;;
;; The resulting SQL from the `foo-query->sql` functions selects all the
;; columns. Thus consumers of those functions may need to wrap that query with
;; another `SELECT` to pull out only the desired columns. Similarly for
;; applying ordering constraints.
;;
(ns com.puppetlabs.puppetdb.query
  (:require [clojure.string :as string]
            [clojure.set :as set]
            [com.puppetlabs.puppetdb.http :refer [v4?]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.jdbc :as jdbc]
            [clj-time.coerce :refer [to-timestamp]]
            [com.puppetlabs.puppetdb.http :refer [remove-all-environments]])
  (:use [puppetlabs.kitchensink.core :only [parse-number keyset valset order-by-expr?]]
        [com.puppetlabs.puppetdb.scf.storage-utils :only [db-serialize sql-as-numeric sql-array-query-string sql-regexp-match sql-regexp-array-match]]
        [com.puppetlabs.jdbc :only [valid-jdbc-query? limited-query-to-vec query-to-vec paged-sql count-sql get-result-count]]
        [com.puppetlabs.puppetdb.query.paging :only [requires-paging?]]
        [clojure.core.match :only [match]]))

(defn execute-paged-query*
  "Helper function to executed paged queries.  Builds up the paged sql string,
  executes the query, and returns map containing the `:result` key and an
  optional `:count` key."
  [fail-limit query {:keys [limit offset order-by count?] :as paging-options}]
  {:pre [(and (integer? fail-limit) (>= fail-limit 0))
         (valid-jdbc-query? query)
         ((some-fn nil? integer?) limit)
         ((some-fn nil? integer?) offset)
         ((some-fn nil? sequential?) order-by)
         (every? order-by-expr? order-by)]
   :post [(map? %)
          (vector? (:result %))
          ((some-fn nil? integer?) (:count %))]}
  (let [[sql & params] (if (string? query) [query] query)
        paged-sql      (paged-sql sql paging-options)
        result         {:result
                          (limited-query-to-vec
                            fail-limit
                            (apply vector paged-sql params))}]
      ;; TODO: this could also be implemented using `COUNT(*) OVER()`,
      ;; which would allow us to get the results and the count via a
      ;; single query (rather than two separate ones).  Need to do
      ;; some benchmarking to see which is faster.
      (if count?
        (assoc result :count
          (get-result-count (apply vector (count-sql sql) params)))
        result)))

(defn execute-query*
  "Helper function to executed non-paged queries.  Returns a map containing the
  `:result` key."
  [fail-limit query]
  {:pre [(and (integer? fail-limit) (>= fail-limit 0))
         (valid-jdbc-query? query)]
   :post [(map? %)
         (vector? (:result %))]}
  {:result (limited-query-to-vec fail-limit query)})

(defn execute-query
  "Given a query and a map of paging options, adds the necessary SQL for
  implementing the paging, executes the query, and returns a map containing
  the results and metadata.

  The return value will contain a key `:result`, whose value is a vector of
  the query results.  If the paging options indicate that a 'total record
  count' should be returned, then the map will also include a key `:count`,
  whose value is an integer indicating the total number of results available."
  ([query paging-options] (execute-query 0 query paging-options))
  ([fail-limit query {:keys [limit offset order-by] :as paging-options}]
   {:pre [((some-fn string? sequential?) query)]
    :post [(map? %)
           (vector? (:result %))
           ((some-fn nil? integer?) (:count %))]}
    (let [sql-and-params (if (string? query) [query] query)]
      (if (requires-paging? paging-options)
        (execute-paged-query* fail-limit sql-and-params paging-options)
        (execute-query* fail-limit sql-and-params)))))

(defn compile-term
  "Compile a single query term, using `ops` as the set of legal operators. This
  function basically just checks that the operator is known, and then
  dispatches to the function implementing it."
  [ops [op & args :as term]]
  (when-not (sequential? term)
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must be an array" (vec term)))))
  (when-not op
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must contain at least one operator" (vec term)))))
  (if-let [f (ops op)]
    (apply f args)
    (throw (IllegalArgumentException. (format "%s is not well-formed: query operator '%s' is unknown" (vec term) op)))))

(defn compile-boolean-operator*
  "Compile a term for the boolean operator `op` (AND or OR) applied to
  `terms`. This is accomplished by compiling each of the `terms` and then just
  joining their `where` terms with the operator. The params are just
  concatenated."
  [op ops & terms]
  {:pre  [(every? coll? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [compiled-terms (map #(compile-term ops %) terms)
        params (mapcat :params compiled-terms)
        query  (->> (map :where compiled-terms)
                    (map #(format "(%s)" %))
                    (string/join (format " %s " (string/upper-case op))))]
    {:where  query
     :params params}))

(def compile-and
  (partial compile-boolean-operator* "and"))

(def compile-or
  (partial compile-boolean-operator* "or"))

(defn negate-term*
  "Compiles `term` and returns the negated version of the query."
  [ops term]
  {:pre  [(sequential? term)]
   :post [(string? (:where %))]}
  (let [compiled-term (compile-term ops term)
        query (format "NOT (%s)" (:where compiled-term))]
    (assoc compiled-term :where query)))

(defn compile-not
  "Compile a NOT operator, applied to `term`. This term simply negates the
  value of `term`. Basically this function just serves as error checking for
  `negate-term*`."
  [version ops & terms]
  {:post [(string? (:where %))]}
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    (do
      (when-not (= (count terms) 1)
        (throw (IllegalArgumentException. (format "'not' takes exactly one argument, but %d were supplied" (count terms)))))
      (negate-term* ops (first terms)))))

;; This map's keys are the queryable fields for facts, and the values are the
;;  corresponding table names where the fields reside
(def fact-columns {"certname" "certname_facts"
                   "name"     "certname_facts"
                   "value"    "certname_facts"
                   "environment" "certname_facts"})

;; This map's keys are the queryable fields for resources, and the values are the
;;  corresponding table names where the fields reside
(def resource-columns {"certname"   "catalogs"
                       "environment" "catalog_resources"
                       "catalog"    "catalog_resources"
                       "resource"   "catalog_resources"
                       "type"       "catalog_resources"
                       "title"      "catalog_resources"
                       "tags"       "catalog_resources"
                       "exported"   "catalog_resources"
                       "file"       "catalog_resources"
                       "line"       "catalog_resources"})

;; This map's keys are the names of fields from the resource table that were
;; renamed in v3 of the query API.  The values are the new names of the fields.
(def v3-renamed-resource-columns {"sourcefile" "file"
                                  "sourceline" "line"})

;; This map's keys are the queryable fields for nodes, and the values are the
;;  corresponding table names where the fields reside
(defn node-columns
  "Return the queryable set of fields and corresponding table names where they reside"
  [version]
  (case version
    (:v1 :v2 :v3)
    {"name"                     "certnames"
     "deactivated"              "certnames"}

    {"name"                     "certnames"
     "deactivated"              "certnames"
     "facts_last_environment"   "certnames"
     "report_last_environment"  "certnames"
     "catalog_last_environment" "certnames"
     "facts_timestamp"          "certnames"
     "report_timestamp"         "certnames"
     "catalog_timestamp"        "certnames"}))

(def event-columns
  {"certname"               ["reports"]
   "configuration_version"  ["reports"]
   "start_time"             ["reports" "run_start_time"]
   "end_time"               ["reports" "run_end_time"]
   "receive_time"           ["reports" "report_receive_time"]
   "report"                 ["resource_events"]
   "status"                 ["resource_events"]
   "timestamp"              ["resource_events"]
   "resource_type"          ["resource_events"]
   "resource_title"         ["resource_events"]
   "property"               ["resource_events"]
   "new_value"              ["resource_events"]
   "old_value"              ["resource_events"]
   "message"                ["resource_events"]
   "file"                   ["resource_events"]
   "line"                   ["resource_events"]
   "containment_path"       ["resource_events"]
   "containing_class"       ["resource_events"]
   "name"                   ["environments" "environment"]})

(defn column-map->sql
  "Helper function that converts one of our column maps to a SQL string suitable
  for use in a SELECT"
  [col-map]
  (string/join ", "
    (for [[field table] col-map]
      (str table "." field))))

(defmulti queryable-fields
  "This function takes a query type (:resource, :fact, :node) and a query
   API version number, and returns a set of strings which are the names the
   fields that are legal to query"
  (fn [query-type query-api-version] query-type))

(defmethod queryable-fields :resource
  [_ query-api-version]
  (case query-api-version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (-> (keyset resource-columns)
            (set/union (keyset v3-renamed-resource-columns))
            (set/difference (valset v3-renamed-resource-columns)))
    (keyset resource-columns)))

(defmethod queryable-fields :fact
  [_ _]
  (keyset fact-columns))

(defmethod queryable-fields :node
  [_ version]
  (keyset (node-columns version)))

(defmethod queryable-fields :event
  [_ _]
  (keyset event-columns))

(def subquery->type
  {"select-resources" :resource
   "select-facts"     :fact})

(defn compile-extract
  "Compile an `extract` operator, selecting the given `field` from the compiled
  result of `subquery`, which must be a kind of `select` operator."
  [query-api-version ops field subquery]
  {:pre [(string? field)
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (let [[subselect & params] (compile-term ops subquery)
        subquery-type (subquery->type (first subquery))]
    (when-not subquery-type
      (throw (IllegalArgumentException. (format "The argument to extract must be a select operator, not '%s'" (first subquery)))))
    (when-not (get (queryable-fields subquery-type query-api-version) field)
      (throw (IllegalArgumentException. (format "Can't extract unknown %s field '%s'. Acceptable fields are: %s" (name subquery-type) field (string/join ", " (sort (queryable-fields subquery-type query-api-version)))))))
    {:where (format "SELECT r1.%s FROM (%s) r1" field subselect)
     :params params}))

(defn compile-in
  "Compile an `in` operator, selecting rows for which the value of
  `field` appears in the result given by `subquery`, which must be an `extract`
  composed with a `select`."
  [kind query-api-version ops field subquery]
  {:pre [(string? field)
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (when-not (get (queryable-fields kind query-api-version) field)
    (throw (IllegalArgumentException. (format "Can't match on unknown %s field '%s' for 'in'. Acceptable fields are: %s" (name kind) field (string/join ", " (sort (queryable-fields kind query-api-version)))))))
  (when-not (= (first subquery) "extract")
    (throw (IllegalArgumentException. (format "The subquery argument of 'in' must be an 'extract', not '%s'" (first subquery)))))
  (let [{:keys [where] :as compiled-subquery} (compile-term ops subquery)]
    (assoc compiled-subquery :where (format "%s IN (%s)" field where))))

(defn resource-query->sql
  "Compile a resource query, returning a vector containing the SQL and
  parameters for the query. All resource columns are selected, and no order is applied."
  [ops query]
  {:post [valid-jdbc-query? %]}
  (let [{:keys [where params]} (compile-term ops query)
        sql (format "SELECT %s
                       FROM (SELECT c.hash as catalog, e.name as environment, catalog_id, resource,
                                    type, title, tags, exported, file, line
                             FROM catalog_resources cr, catalogs c LEFT OUTER JOIN environments e
                                  on c.environment_id = e.id
                             WHERE c.id = cr.catalog_id) AS catalog_resources
                       JOIN catalogs ON catalog_resources.catalog_id = catalogs.id
                     WHERE %s"
                    (column-map->sql resource-columns) where)]
    (apply vector sql params)))

(defn fact-query->sql
  "Compile a fact query, returning a vector containing the SQL and parameters
  for the query. All fact columns are selected, and no order is applied."
  [ops query]
  {:post [valid-jdbc-query? %]}
  (let [{:keys [where params]} (compile-term ops query)
        sql (format "SELECT %s FROM (select cf.certname, cf.name, cf.value, env.name as environment
                                     FROM certname_facts cf INNER JOIN certname_facts_metadata cfm on cf.certname = cfm.certname
                                                            LEFT OUTER JOIN environments as env on cfm.environment_id = env.id)
                                     as certname_facts
                     WHERE %s" (column-map->sql fact-columns) where)]
    (apply vector sql params)))

(defn node-query->sql
  "Compile a node query, returning a vector containing the SQL and parameters
  for the query. All node columns are selected, and no order is applied."
  [version ops query]
  {:post [valid-jdbc-query? %]}
  (let [sql (case version
              (:v1 :v2 :v3)
              (format "SELECT %s FROM certnames"
                      (column-map->sql (node-columns version)))

              (format "SELECT %s FROM (SELECT
                       certnames.name,
                       certnames.deactivated,
                       catalogs.timestamp AS catalog_timestamp,
                       certname_facts_metadata.timestamp AS facts_timestamp,
                       reports.end_time AS report_timestamp,
                       catalog_environment.name AS catalog_last_environment,
                       facts_environment.name AS facts_last_environment,
                       reports_environment.name AS report_last_environment
                       FROM certnames
                         LEFT OUTER JOIN catalogs
                           ON certnames.name = catalogs.certname
                         LEFT OUTER JOIN certname_facts_metadata
                           ON certnames.name = certname_facts_metadata.certname
                         LEFT OUTER JOIN reports
                           ON certnames.name = reports.certname
                             AND reports.hash
                               IN (SELECT report FROM latest_reports)
                         LEFT OUTER JOIN environments AS catalog_environment
                           ON catalog_environment.id = catalogs.environment_id
                         LEFT OUTER JOIN environments AS facts_environment
                           ON facts_environment.id = certname_facts_metadata.environment_id
                         LEFT OUTER JOIN environments AS reports_environment
                           ON reports_environment.id = reports.environment_id) as certnames"
                      (column-map->sql (node-columns version))))]
    (if query
      (let [{:keys [where params]} (compile-term ops query)
            sql (str sql (format " WHERE %s" where))]
        (apply vector sql params))
      (vector sql))))

(defn compile-resource-equality
  "Compile an = operator for a resource query. `path` represents the field
  to query against, and `value` is the value. This mostly just defers to
  `compile-resource-equality-v2`, with a little bit of logic to handle the one
  term that differs."
  [version & [path value :as args]]
  {:post [(map? %)
          (:where %)]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (do
          ;; If they passed in any of the new names for the renamed resource-columns, we fail
          ;; because this is v2.
          (when (contains? (valset v3-renamed-resource-columns) path)
            (throw (IllegalArgumentException. (format "%s is not a queryable object for resources" path))))
          (compile-resource-equality :v3 (get v3-renamed-resource-columns path path) value))
    (match [path]
           ;; tag join. Tags are case-insensitive but always lowercase, so
           ;; lowercase the query value.
           ["tag"]
           {:where  (sql-array-query-string "tags")
            :params [(string/lower-case value)]}

           ;; node join.
           ["certname"]
           {:where  "catalogs.certname = ?"
            :params [value]}

           ["environment" :guard (v4? version)]
           {:where  "catalog_resources.environment = ?"
            :params [value]}

           ;; {in,}active nodes.
           [["node" "active"]]
           {
            :where (format "catalogs.certname IN (SELECT name FROM certnames WHERE deactivated IS %s)" (if value "NULL" "NOT NULL"))}

           ;; param joins.
           [["parameter" (name :guard string?)]]
           {:where  "catalog_resources.resource IN (SELECT rp.resource FROM resource_params rp WHERE rp.name = ? AND rp.value = ?)"
            :params [name (db-serialize value)]}

           ;; metadata match.
           [(metadata :guard #{"catalog" "resource" "type" "title" "tags" "exported" "file" "line"})]
           {:where  (format "catalog_resources.%s = ?" metadata)
            :params [value]}

           ;; ...else, failure
           :else (throw (IllegalArgumentException.
                         (format "'%s' is not a queryable object for resources in the version %s API" path (last (name version))))))))

(defn compile-resource-regexp
  "Compile an '~' predicate for a resource query, which does regexp matching.
  This is done by leveraging the correct database-specific regexp syntax to
  return only rows where the supplied `path` match the given `pattern`."
  [version & [path value :as args]]
  {:post [(map? %)
          (:where %)]}
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (do
          (when (contains? (valset v3-renamed-resource-columns) path)
            (throw (IllegalArgumentException. (format "%s cannot be the target of a regexp match" path))))
          (compile-resource-regexp :v3 (get v3-renamed-resource-columns path path) value))
    (match [path]
           ["tag"]
           {:where (sql-regexp-array-match "catalog_resources" "tags")
            :params [value]}

           ;; node join.
           ["certname"]
           {:where  (sql-regexp-match "catalogs.certname")
            :params [value]}

           ["environment" :guard (v4? version)]
           {:where  (sql-regexp-match "catalog_resources.environment")
            :params [value]}

           ;; metadata match.
           [(metadata :guard #{"type" "title" "exported" "file"})]
           {:where  (sql-regexp-match (format "catalog_resources.%s" metadata))
            :params [value]}

           ;; ...else, failure
           :else (throw (IllegalArgumentException.
                         (format "'%s' cannot be the target of a regexp match for version %s of the resources API" path (last (name version))))))))

(defn compile-fact-equality
  "Compile an = predicate for a fact query. `path` represents the field to
  query against, and `value` is the value."
  [version]
  (fn [path value]
    {:post [(map? %)
            (:where %)]}
    (match [path]
           ["name"]
           {:where "certname_facts.name = ?"
            :params [value]}

           ["value"]
           {:where "certname_facts.value = ?"
            :params [(str value)]}

           ["certname"]
           {:where "certname_facts.certname = ?"
            :params [value]}

           ["environment" :guard (v4? version)]
           {:where "certname_facts.environment = ?"
            :params [value]}

           [["node" "active"]]
           {:where (format "certname_facts.certname IN (SELECT name FROM certnames WHERE deactivated IS %s)" (if value "NULL" "NOT NULL"))}

           :else
           (throw (IllegalArgumentException. (format "%s is not a queryable object for version %s of the facts query api" path (last (name version))))))))

(defn compile-fact-regexp
  "Compile an '~' predicate for a fact query, which does regexp matching.  This
  is done by leveraging the correct database-specific regexp syntax to return
  only rows where the supplied `path` match the given `pattern`."
  [version]
  (fn [path pattern]
    {:pre [(string? path)
           (string? pattern)]
     :post [(map? %)
            (string? (:where %))]}
    (let [query (fn [col] {:where (sql-regexp-match col) :params [pattern]})]
      (match [path]
             ["certname"]
             (query "certname_facts.certname")

             ["environment" :guard (v4? version)]
             (query "certname_facts.environment")

             ["name"]
             (query "certname_facts.name")

             ["value"]
             (query "certname_facts.value")

             :else (throw (IllegalArgumentException.
                           (format "%s is not a valid version %s operand for regexp comparison" path (last (name version)))))))))

(defn compile-fact-inequality
  "Compile a numeric inequality for a fact query (> < >= <=). The `value` for
  comparison must be either a number or the string representation of a number.
  The value in the database will be cast to a float or an int for comparison,
  or will be NULL if it is neither."
  [op path value]
  {:pre [(string? path)]
   :post [(map? %)
          (string? (:where %))]}
  (if-let [number (parse-number (str value))]
    (match [path]
           ["value"]
           ;; This is like convert_to_numeric(certname_facts.value) > 0.3
           {:where  (format "%s %s ?" (sql-as-numeric "certname_facts.value") op)
            :params [number]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for facts"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(defn compile-node-equality
  "Compile an equality operator for nodes. This can either be for the value of
  a specific fact, or based on node activeness."
  [version path value]
  {:post [(map? %)
          (string? (:where %))]}
  (let [std-fields (case version
                     (:v1 :v2 :v3) #{"name"}
                     #{"name" "facts-last-environment" "catalog-last-environment" "report-last-environment"})]
    (match [path]
           [(field :guard std-fields)]
           {:where (format "%s = ?" (jdbc/dashes->underscores field))
            :params [value] }

           [["fact" (name :guard string?)]]
           {:where  "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND cf.value = ?)"
            :params [name (str value)]}

           [["node" "active"]]
           {:where (format "certnames.deactivated IS %s" (if value "NULL" "NOT NULL"))}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for nodes"))))))

(defn compile-node-regexp
  "Compile an '~' predicate for a fact query, which does regexp matching.  This
  is done by leveraging the correct database-specific regexp syntax to return
  only rows where the supplied `path` match the given `pattern`."
  [version path pattern]
  {:pre [(string? pattern)]
   :post [(map? %)
          (string? (:where %))]}
  (let [query (fn [col] {:where (sql-regexp-match col) :params [pattern]})
        std-fields (case version
                     (:v1 :v2 :v3) #{"name"}
                     #{"name" "facts-last-environment" "catalog-last-environment" "report-last-environment"})]
    (match [path]
           [(field :guard std-fields)]
           {:where (sql-regexp-match (jdbc/dashes->underscores field))
            :params [pattern]}

           [["fact" (name :guard string?)]]
           {:where (format "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND %s)" (sql-regexp-match "cf.value"))
            :params [name pattern]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a valid operand for regexp comparison"))))))

(defn compile-node-inequality
  [op path value]
  {:post [(map? %)
          (string? (:where %))]}
  (if-let [number (parse-number (str value))]
    (match [path]
           [["fact" (name :guard string?)]]
           {:where  (format "certnames.name IN (SELECT cf.certname FROM certname_facts cf WHERE cf.name = ? AND %s %s ?)" (sql-as-numeric "cf.value") op)
            :params [name number]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for nodes"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(defn compile-resource-event-inequality
  "Compile a timestamp inequality for a resource event query (> < >= <=).
  The `value` for comparison must be coercible to a timestamp via
  `clj-time.coerce/to-timestamp` (e.g., an ISO-8601 compatible date-time string)."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count
args))))))

  (let [timestamp-fields {"timestamp"           "resource_events.timestamp"
                          "run-start-time"      "reports.start_time"
                          "run-end-time"        "reports.end_time"
                          "report-receive-time" "reports.receive_time"}]
    (match [path]
      [(field :guard (kitchensink/keyset timestamp-fields))]
      (if-let [timestamp (to-timestamp value)]
        {:where (format "%s %s ?" (timestamp-fields field) op)
         :params [(to-timestamp value)]}
        (throw (IllegalArgumentException. (format "'%s' is not a valid timestamp value" value))))

      :else (throw (IllegalArgumentException.
                     (str op " operator does not support object '" path "' for resource events"))))))

(defn compile-resource-event-equality
  "Compile an = predicate for resource event query. `path` represents the field to
  query against, and `value` is the value."
  [version]
  (fn [& [path value :as args]]
    {:post [(map? %)
            (string? (:where %))]}
    (when-not (= (count args) 2)
      (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
    (let [path (jdbc/dashes->underscores path)]
      (match [path]
             ["certname"]
             {:where (format "reports.certname = ?")
              :params [value]}

             ["latest_report?"]
             {:where (format "resource_events.report %s (SELECT latest_reports.report FROM latest_reports)"
                             (if value "IN" "NOT IN"))}

             ["environment" :guard (v4? version)]
             {:where "environments.name = ?"
              :params [value]}

             [(field :guard #{"report" "resource_type" "resource_title" "status"})]
             {:where (format "resource_events.%s = ?" field)
              :params [value] }

             ;; these fields allow NULL, which causes a change in semantics when
             ;; wrapped in a NOT(...) clause, so we have to be very explicit
             ;; about the NULL case.
             [(field :guard #{"property" "message" "file" "line" "containing_class"})]
             (if-not (nil? value)
               {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
                :params [value] }
               {:where (format "resource_events.%s IS NULL" field)
                :params nil })

             ;; these fields require special treatment for NULL (as described above),
             ;; plus a serialization step since the values can be complex data types
             [(field :guard #{"old_value" "new_value"})]
             {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
              :params [(db-serialize value)] }

             :else (throw (IllegalArgumentException.
                           (format "'%s' is not a queryable object for version %s of the resource events API" path (last (name version)))))))))

(defn compile-resource-event-regexp
  "Compile an ~ predicate for resource event query. `path` represents the field
   to query against, and `pattern` is the regular expression to match."
  [version]
  (fn [& [path pattern :as args]]
    {:post [(map? %)
            (string? (:where %))]}
    (when-not (= (count args) 2)
      (throw (IllegalArgumentException. (format "~ requires exactly two arguments, but %d were supplied" (count args)))))
    (let [path (jdbc/dashes->underscores path)]
      (match [path]
             ["certname"]
             {:where (sql-regexp-match "reports.certname")
              :params [pattern]}

             ["environment" :guard (v4? version)]
             {:where (sql-regexp-match "environments.name")
              :params [pattern]}

             [(field :guard #{"report" "resource_type" "resource_title" "status"})]
             {:where  (sql-regexp-match (format "resource_events.%s" field))
              :params [pattern] }

             ;; these fields allow NULL, which causes a change in semantics when
             ;; wrapped in a NOT(...) clause, so we have to be very explicit
             ;; about the NULL case.
             [(field :guard #{"property" "message" "file" "line" "containing_class"})]
             {:where (format "%s AND resource_events.%s IS NOT NULL"
                             (sql-regexp-match (format "resource_events.%s" field))
                             field)
              :params [pattern] }

             :else (throw (IllegalArgumentException.
                           (format "'%s' is not a queryable object for version %s of the resource events API" path (last (name version)))))))))

(declare fact-operators)

(defn resource-operators
  "Maps resource query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    (fn [op]
      (condp = (string/lower-case op)
        "=" (partial compile-resource-equality version)
        "~" (partial compile-resource-regexp version)
        "and" (partial compile-and (resource-operators version))
        "or" (partial compile-or (resource-operators version))
        "not" (partial compile-not version (resource-operators version))
        "extract" (partial compile-extract version (resource-operators version))
        "in" (partial compile-in :resource version (resource-operators version))
        "select-resources" (partial resource-query->sql (resource-operators version))
        "select-facts" (partial fact-query->sql (fact-operators version))
        nil))))

(defn fact-operators
  "Maps fact query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    (fn [op]
      (let [op (string/lower-case op)]
        (cond
          (#{">" "<" ">=" "<="} op)
          (partial compile-fact-inequality op)

          (= op "=") (compile-fact-equality version)
          (= op "~") (compile-fact-regexp version)
          ;; We pass this function along so the recursive calls know which set of
          ;; operators/functions to use, depending on the API version.
          (= op "and") (partial compile-and (fact-operators version))
          (= op "or") (partial compile-or (fact-operators version))
          (= op "not") (partial compile-not version (fact-operators version))
          (= op "extract") (partial compile-extract version (fact-operators version))
          (= op "in") (partial compile-in :fact version (fact-operators version))
          (= op "select-resources") (partial resource-query->sql (resource-operators version))
          (= op "select-facts") (partial fact-query->sql (fact-operators version)))))))

(defn node-operators
  "Maps node query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    (fn [op]
      (let [op (string/lower-case op)]
        (cond
          (= op "=") (partial compile-node-equality version)
          (= op "~") (partial compile-node-regexp version)
          (#{">" "<" ">=" "<="} op) (partial compile-node-inequality op)
          (= op "and") (partial compile-and (node-operators version))
          (= op "or") (partial compile-or (node-operators version))
          (= op "not") (partial compile-not version (node-operators version))
          (= op "extract") (partial compile-extract version (node-operators version))
          (= op "in") (partial compile-in :node version (node-operators version))
          (= op "select-resources") (partial resource-query->sql (resource-operators version))
          (= op "select-facts") (partial fact-query->sql (fact-operators version)))))))

(defn resource-event-ops
  "Maps resource event query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (throw (IllegalArgumentException. (str "Resource events end-point not available for api version " version)))
    :v3 (fn [op]
          (let [op (string/lower-case op)]
            (cond
              (= op "=") (compile-resource-event-equality version)
              (= op "and") (partial compile-and (resource-event-ops version))
              (= op "or") (partial compile-or (resource-event-ops version))
              (= op "not") (partial compile-not version (resource-event-ops version))
              (#{">" "<" ">=" "<="} op) (partial compile-resource-event-inequality op)
              (= op "~") (compile-resource-event-regexp version))))
    (fn [op]
      (let [op (string/lower-case op)]
        (cond
          (= op "=") (compile-resource-event-equality version)
          (= op "and") (partial compile-and (resource-event-ops version))
          (= op "or") (partial compile-or (resource-event-ops version))
          (= op "not") (partial compile-not version (resource-event-ops version))
          (#{">" "<" ">=" "<="} op) (partial compile-resource-event-inequality op)
          (= op "~") (compile-resource-event-regexp version)
          (= op "extract") (partial compile-extract version (resource-event-ops version))
          (= op "in") (partial compile-in :event version (resource-event-ops version))
          (= op "select-resources") (partial resource-query->sql (resource-operators version))
          (= op "select-facts") (partial fact-query->sql (fact-operators version)))))))

(defn streamed-query-result
  "Uses a cursored resultset (for streaming), removing environments when not
   in version ;v4. Returns a function that accepts a single function. That function
   with get the results of the query"
  [db version sql params]
  (fn [f]
    (jdbc/with-transacted-connection db
      (jdbc/with-query-results-cursor sql params rs
        (f (remove-all-environments version rs))))))
