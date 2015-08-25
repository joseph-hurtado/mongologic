(ns mongologic.core
  (:refer-clojure :exclude [find count update])
  (:require [somnium.congomongo :as db]
            [clj-time.core :as time]
            [taoensso.timbre :as log])
  (:load "coerce"))


; #TODO
; attributes ending with _id should be automatically converted to ObjectIds with to-object-id,
; or maybe better, it should be possible to specify the attribute types of a
; collection (dates and ids at least) and then Mongologic could convert
; attribute values to the appropriate types when necessary, similarly to how
; it's done in ActiveRecord (where the types are defined in the migrations)
; (and Mongoid?)


; In the namespace of each of the models define the entity that will be
; passed as the first parameter to any call to a mongologic function. An
; entity is a map like this...
;
; (def user-entity
;   {:collection :users
;    :validator valid?
;    :before-save prepare-for-save
;    :before-create set-default-values})
;
; The values for :validator, :before-save, and :before-create must be
; functions accepting a map (representing a record) as a parameter.
; The :validator function must return a truthy or falsey value.
; The :before-save function must return the (possibly updated) record map.
; The :before-create function must return the (possibly updated) record map.
; If the entity doesn't require validation or a before-save hook, leave out
; the corresponding element.
;
; References:
; http://elhumidor.blogspot.com.es/2012/11/why-not-to-use-my-library-clj-record.html
; http://guides.rubyonrails.org/active_record_validations_callbacks.html
; http://sqlkorma.com

;; Passing the `entity` map as a parameter to the lifecycle callback
;; functions allows these functions to be more self-contained, which is
;; always a good thing, but is especially useful when a callback is defined
;; in a namespace other than the one defining the entity as it helps reduce
;; coupling. It also makes easier to reuse callbacks across models.
;; #TODO:
;; Should the :validator callback be passed the `entity` map as a parameter
;; too?


; http://items.sjbach.com/567/critiquing-clojure#2
(declare to-object-id)


;; Mongologic, like Rails' ActiveRecord, allows to hook more than one
;; function into the same slot in the record's life cycle.
;; http://books.google.es/books?id=slwLAqkT_Y0C&lpg=PT397&ots=9b2wBJjAxO&dq=callback%20queue%20rails&pg=PT397#v=onepage&q=callback%20queue%20rails&f=false
;; (For the moment, Mongologic only allows this for :after-update and
;; :after-delete)
;;
;; Unlike ActiveRecord, Mongologic allows to hook functions from a model's
;; namespace into the callback queues of another model. For example, an
;; ActiveRecord Order model configured like
;;
;;    class Order < ActiveRecord::Base
;;      belongs_to :customer, dependent: :destroy,
;;
;; will be automatically destroyed when @customer.destroy is called [^1].
;; In Mongologic the same can be achieved by hooking the appropriate
;; function in the order namespace into the customer's :before-delete
;; callback queue (actually not a good example because callback queues
;; are not supported yet on :before-delete, but the example will still
;; hopefully be useful):
;;
;;    (ns order)
;;    ...
;;    (swap! (get-in customer-component [:entity :before-delete])
;;           mongologic/add-to-callback-queue
;;           ;; destroy associated orders
;;           (partial handle-customer-delete order-component))
;;
;; Sierra's component [^2] or similar could be used to specify that the order
;; component uses the customer component, so that the latter is available
;; from the namespace of the former.
;;
;; Notice that the customer namespace won't contain any reference to orders
;; (it's the orders that use the customers, not the other way around).
;;
;; [^1]: http://guides.rubyonrails.org/association_basics.html#why-associations-questionmark
;; [^2]: https://github.com/stuartsierra/component
(defn add-to-callback-queue
  "#IMPORTANT
  Callback queues currently only supported in :after-update and :after-delete

  Example:

       (swap! (get-in component [:entity :after-update])  ; must be an atom
              add-to-callback-queue
              new-callback-fn)"
  [callback-queue callback-fn]
  (log/debug "add-to-callback-queue")
  ;; http://stackoverflow.com/a/10807774
  ;; http://www.brainonfire.net/files/seqs-and-colls/main.html
  (cond
    (sequential? callback-queue) (conj callback-queue callback-fn)
    (nil? callback-queue) [callback-fn]
    :else (conj [callback-queue] callback-fn)))



;; #TODO
;; Currently Mongologic depends on noir.validation . To get rid of this
;; dependency a more general way to handle validations is needed.
;;
;; How Mongologic can decide if validation failed or succeeded, what
;; Mongologic functions will return in each case?
;;
;; - Verily (https://github.com/jkk/verily#validation-function-contract)
;; If validation succeeded, return nil or an empty collection. 
;; ()
;; If there was a
;; problem, return a problem map or collection of problem maps.
;; ({:keys (:email), :msg "must not be blank"})
;;
;; - Validateur (http://clojurevalidations.info/articles/getting_started.html#usage)
;; \"To retrive a map of keys to error messages simply call the validator with a map:\"
;;
;; OTOH, Mongologic create/update functions must return the map of the
;; created/updated record if everything succeeds, or the collection (map,
;; vector...) of errors if validations fail, in such a way that the client
;; can distinguish if it's one or the other.
;;
;; An option may be to return `[true updated-record]` on successful update,
;; and `[false validator-result-or-update-error]` when something fails (similar to
;; Validateur's validator functions). Somehow similar to Rails, where update
;; returns false if it fails
;; http://api.rubyonrails.org/classes/ActiveRecord/Persistence.html#method-i-update
;; That probably makes more sense than
;;   [params nil] whenever everything is ok, and [nil msg] whenever they're not.
;; http://adambard.com/blog/acceptable-error-handling-in-clojure/
;;
;; See:
;; http://api.rubyonrails.org/classes/ActiveRecord/Persistence/ClassMethods.html#method-i-create
;; http://api.rubyonrails.org/classes/ActiveRecord/Validations/ClassMethods.html#method-i-create-21
(defn create
  "These callbacks will be called, in the order listed here, if defined in
  the map under the :entity key of the `model-component` parameter:
  - :before-validation
  - :before-validation-on-create
  - :validator
  - :before-save
  - :before-create
  - :after-create

  All callbacks will be passed the `model-component` and the entire record
  with the corresponding attributes updated. The :validator callback must
  return a collection of errors (empty or nil if no errors), the other
  callbacks must return the entire record, maybe with some attributes
  changed, added, or deleted.

  Returns:
    - [false validation-errors] if validations fail (where validation-errors
    is what the validator returned)
    - [false {:base [:insert-error]}] if insertion into the database fails
    - [true created-object] otherwise (created-object will have an :_id and
      :created_at and :updated_at timestamps)"
  [{:keys [database entity] :as model-component} attributes]
  ; The :before-validation-on-create hook is similar to the old
  ; `before_validation_on_create` callback in Rails (in Rails 3 it was replaced
  ; with
  ;   before_validation :do_something, :on => :create
  ; )
  ; http://stackoverflow.com/a/11242617
  ; http://guides.rubyonrails.org/active_record_validations.html#on
  ; http://guides.rubyonrails.org/v2.3.11/activerecord_validations_callbacks.html#available-callbacks

  ; The :before-create hook can be used, for example, to provide default
  ; values.
  ; Mongoid provides a way to specify default values for fields
  ; http://mongoid.org/en/mongoid/docs/documents.html#field_defaults
  ; The equivalent Rails' before_create callback can be used for this too,
  ; see example in
  ; http://api.rubyonrails.org/classes/ActiveRecord/Callbacks.html
  ; How to set default values in ActiveRecord?
  ; https://pinboard.in/u:xavi/b:616b9fb6d8c7
  ;
  (let [collection
          (:collection entity)
        validate
          (:validator entity)
        empty-callback-fn
          (fn [c r] r)
        before-validation-hook
          (or (:before-validation entity) empty-callback-fn)
        before-validation-on-create-hook
          (or (:before-validation-on-create entity) empty-callback-fn)
        before-save-hook
          (or (:before-save entity) empty-callback-fn)
        before-create-hook
          (or (:before-create entity) empty-callback-fn)
        after-create-hook
          (or (:after-create entity) empty-callback-fn)
        attributes
          ; Like in Rails 2, :before-validation is executed before :before-validation-on-create
          ; http://api.rubyonrails.org/v2.3.11/classes/ActiveRecord/Callbacks.html
          ; Differently than Rails, both callback functions must return the
          ; entire record (in Rails, "If a before_* callback returns false,
          ; all the later callbacks and the associated action are
          ; cancelled").
          ; http://api.rubyonrails.org/classes/ActiveRecord/Callbacks.html#module-ActiveRecord::Callbacks-label-before_validation%2A+returning+statements
          (->> attributes
               (before-validation-hook model-component)
               (before-validation-on-create-hook model-component))
        validation-errors
          (and validate (validate model-component attributes))]
    (if (seq validation-errors)
      [false validation-errors]
      ; If "write concern" is set to :safe,
      ; "Exceptions are raised for network issues, and server errors;
      ; waits on a server for the write operation"
      ; http://api.mongodb.org/java/2.7.3/com/mongodb/WriteConcern.html#SAFE
      ; "server errors" may be, for example, duplicate key errors
      ;
      ; DuplicateKey E11000 duplicate key error index:
      ; heroku_app2289247.users.$email_1  dup key: { : null }
      ; com.mongodb.CommandResult.getException (CommandResult.java:85)
      ; https://github.com/aboekhoff/congomongo/pull/62#issuecomment-5249364
      ;
      ; If :created_at is specified then that will be used instead of
      ; the current timestamp (like in Rails). This also works if a
      ; nil value is specified. (Rails, instead, doesn't honor a
      ; :created_at set to nil, and overwrites it with the current
      ; timestamp.)
      ; Same for :updated_at .
      (let [now
              (time/now)
            record
              (merge (->> attributes
                          (before-save-hook model-component)
                          (before-create-hook model-component))
                     (when-not (contains? attributes :created_at)
                               {:created_at now})
                     (when-not (contains? attributes :updated_at)
                               {:updated_at now}))
            record
              (try
                (db/with-mongo (:connection database)
                  ; insert! returns the inserted object,
                  ; with the :_id set
                  (db/insert! collection record))
                (catch Exception e
                  (log/info (str "log-message=\"in create\" exception=" e
                                 " collection=" (:collection entity)
                                 " record=" record))
                  nil))]
        (if record
          [true (after-create-hook model-component record)]
          ;; #TODO
          ;; Maybe the exception should be raised, maybe the error
          ;; should be in a set like in Validateur
          [false {:base [:insert-error]}])))))

; In Ruby on Rails, `find` also accepts an id, or an array of ids
; http://api.rubyonrails.org/classes/ActiveRecord/FinderMethods.html#method-i-find
; I thought of replicating this (ex. use $in when passed an array o ids)
; using a *protocol*, but in a Clojure protocol the function to execute is
; determined by the type of the first argument, and that's not helpful here.
; What would really be needed here is to dispatch on the type of the second
; argument.
; http://clojure.org/protocols
(defn find
  [{:keys [database entity] :as model-component}
   & [{:keys [where sort limit explain?]}]]
  ; Congomongo's fetch uses keyword parameters, so the way to call fetch is...
  ;     (fetch :users :limit limit)
  ; instead of...
  ;     (fetch :users {:limit limit})
  ;   https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj#L264
  ; More about keyword params in Clojure at
  ;   http://stackoverflow.com/questions/717963/clojure-keyword-arguments
  ; MongoDB query syntax
  ; http://docs.mongodb.org/manual/core/read-operations/
  ;
  ; Core Explain Output Fields
  ; http://docs.mongodb.org/manual/reference/method/cursor.explain/#explain-output-fields-core
  (db/with-mongo (:connection database)
    (db/fetch (:collection entity)
              :where where
              :sort sort
              :limit limit
              :explain? explain?)))

; http://docs.mongodb.org/manual/reference/method/db.collection.findOne/
; Cannot be used with :sort, use fetch with :limit 1 instead
; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
(defn find-one
  [{:keys [database entity] :as model-component} where]
  (db/with-mongo (:connection database)
    (db/fetch-one (:collection entity) :where where)))

(defn find-by-id
  [model-component id]
  (find-one model-component
            {:_id (if (string? id) (to-object-id id) id)}))

; #TODO maybe the second param should be the where map, instead of {:where where-map}
(defn count
  [{:keys [database entity] :as model-component} & [{:keys [where]}]]
  (db/with-mongo (:connection database)
    (db/fetch-count (:collection entity) :where where)))

(defn find-contiguous
  "Returns the document/record that follows (if `order` is 1, the default) or
  precedes (if `order` is -1) the specified `document` when the documents
  matching `conditions` are ordered by `attribute-name`.

  Ex.
  (find-contiguous post-component current-post :posted_at {:author_id <id>} 1)"
  [model-component document attribute-name conditions & [order]]
  (log/debug "At find-contiguous")
  (let [comparison-operator
          (if (= order -1) :$lte :$gte)
        base-conditions
          [{attribute-name {comparison-operator (attribute-name document)}}
           {:_id {:$ne (:_id document)}}]
        all-conditions
          (if conditions (conj base-conditions conditions) base-conditions)]
    (log/debug "attribute-name:" attribute-name)
    (log/debug "order:" order)
    (log/debug "all-conditions:" all-conditions)
    (first (find model-component
                 {:where {:$and all-conditions}
                  :sort {attribute-name order, :_id order}
                  :limit 1}))))

; Reference about the naming
; http://msp.gsfc.nasa.gov/npas/documents/reference/Database/find_next.html
;
; If specified, 'conditions' must be a map containing a MongoDB/CongoMongo
; expression
; http://docs.mongodb.org/manual/reference/operator/and/
(defn find-next [model-component document attribute-name & [conditions]]
  (find-contiguous model-component document attribute-name conditions 1))

(defn find-prev [model-component document attribute-name & [conditions]]
  (find-contiguous model-component document attribute-name conditions -1))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pagination

;; #TODO Move these pagination functions to their own namespace?


; Range-based pagination [1][2]
; ----------------------
;
; `start` param could be used to specify the first record of the desired page
; (i.e. it wouldn't specify a page number)
; The pagination function should take a `start` value as input, and return the
; records for that page plus the `start` values for the previous and next pages.
;
; If `start` specifies the id of the first record in the requested page, then
; the server will have to query the database to get that record first, and
; then, if results are ordered by updated_at for example, it will have to do
; another query using the id and updated_at of that record to get the rest of
; of the records of the request page.
;
; The problem of that approach is that 3 queries are required:
; + one to get the record specified in `start`,
; + another to get the rest of the records in the requested page (plus once
; more, which will used to build the link to the next page)
; + and yet another to get the id of the previous page's first record (to build
; the link to the previous page)
;
; Another problem is that if the record specified in the `start` parameter
; got its sort fields updated, or was deleted, since the link was generated,
; then the link may not show the expected results, or will not work at all.
;
; Another approach would be to specify the requested page using the sort
; fields values of its first record, plus the id (to disambiguate when
; necessary). All these values could be specified in a Clojure vector or map,
; and then URL encode an EDN representation of that data structure, and pass
; the resulting value in `start` parameter [3].
;
; With this latter approach only 2 queries would be needed (which would be
; similar to the last two queries of the first approach).
;
; Another advantage of the latter approach is that even if the record
; specified in the `start` parameter is updated or deleted, the link can
; still work as expected. Besides, and more important, the link is more
; "semantic", allowing to build a link "manually" to get the desired page
; (ex. to get records updated around April 2010, just use something like
; `start=2010-04-15`).
;
; So, this latter approach will be used.
;
;
; [1] skip()
; http://docs.mongodb.org/manual/reference/method/cursor.skip/
; [2] MongoDB ranged pagination
; http://stackoverflow.com/questions/9703319/mongodb-ranged-pagination
; (this is how Google does it)
; [3] Similar to what's done in
; http://ragnard.github.io/2013/08/12/datomic-in-the-browser.html
;


;;         paging-conditions


          ; $gte or $lte depending on if sorting is ascending (1) or
          ; descending (-1)
          ;
          ; [{:sort_field_1 {:$gte (:sort_field_1 start)}}
          ;  {:sort_field_2 {:$lte (:sort_field_2 start)}}
          ;  ...
          ;  {:_id {:$gte {:_id start}}}]
          ;

;;           (mapv (fn [[field order]]
;;                   {field {(if (= order 1)
;;                               :$gte
;;                               :$lte)
;;                           (field start)}})
;;                 full-sort-order)
          ;
          ; UPDATE:
          ; That's wrong!
          ; Correct way should be something like (in pseudo-SQL)...
          ;
          ;    :sort_field_1 > X
          ; OR (:sort_field_1 = X AND :sort_field_2 < Y)
          ; OR (:sort_field_1 = X AND :sort_field_2 = Y AND :_id > Z)
          ;
          ; In MongoDB...
          ;
          ; {$or [{:sort_field_1 {:$gt (:sort_field_1 start)}}
          ;       {:$and [{:sort_field_1 (:sort_field_1 start)}
          ;               {:sort_field_2 {:$lt (:sort_field_2 start)}}]}
          ;       {:$and [{:sort_field_1 (:sort_field_1 start)}
          ;               {:sort_field_2 (:sort_field_2 start)}
          ;               {:_id {:$gt (:_id start)}}]}]}
          ;
          ; For the moment it will be assumed that there's only 1 field in sort-order
          ; {$or [{:sort_field_1 {:$gt/:$lt (:sort_field_1 start)}}
          ;       {:$and [{:sort_field_1 (:sort_field_1 start)}
          ;               {:_id {:$gt (:_id start)}}]}]}
          ;
          ; So...
(defn- generate-paging-conditions
  [sort-order start]
  (let [[sort-field-1-key sort-field-1-order]
          (first sort-order)  ; sort-order is an array-map, so it's ordered
        sort-field-1-value
          ; #TODO
          ; The :sort map (sort-order) should have the same keys as the start
          ; map except, maybe, :_id
          ;
          ; Idiomatic way to check for not empty
          ; http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/empty?
          (when (seq sort-order)
            (sort-field-1-key start))
        comparison-operator
          (if (= sort-field-1-order 1) :$gt :$lt)
        ; assumes last element corresponds to the attribute used to
        ; disambiguate order, typically _id
        id-comparison-operator
          (if (= (val (last sort-order)) 1) :$gte :$lte)]
    ;
    ; `:sort` (sort-order) may have not been specified
    ; `start` may have not been specified
    (if (and sort-order
             (not (every? #{:_id} (keys sort-order)))
             (seq start))
        {:$or [{sort-field-1-key {comparison-operator sort-field-1-value}}
               {:$and [{sort-field-1-key sort-field-1-value}
                       {:_id {id-comparison-operator (:_id start)}}]}]}
        (when (seq start)
              {:_id {id-comparison-operator (:_id start)}}))))

;; connecting 2 groups of conditions with AND
;; http://stackoverflow.com/questions/6435389/intersection-of-two-relations
;;
;; Just like WHERE clauses scopes are merged using AND conditions.
;; http://guides.rubyonrails.org/active_record_querying.html#merging-of-scopes
(defn- merge-conditions-with-and
  "It also works when `conditions-1` and `conditions-2` are nil."
  [conditions-1 conditions-2]
  (if (and (seq conditions-1) (seq conditions-2))
      {:$and [conditions-1 conditions-2]}
      (if (seq conditions-1)
          conditions-1
          conditions-2)))

;; #TODO the start in the example is not realistic
;; it should contain values for sort fields too.
(defn page
  "Ex.
      (page post-component
            ; The default sort order is `:sort {:_id 1}`. If the :sort map
            ; has more than 1 element, it should be an array map, which
            ; maintains key order.
            ; When the whole map is nil, it pages through all records with
            ; the default sort order.
            {:where {:author_id <author_id>} :sort {:posted_at -1}}
            ; start, when it's nil the 1st page is returned
            {:_id (mongologic/to-object-id \"51b5da900364618037ff21e7\")}
            ; page-size
            100)

  Returns:
      {:items
       :previous-page-start
       :next-page-start}"
  ; References:
  ; + https://docs.djangoproject.com/en/1.5/topics/pagination/
  ; + Kaminari works with Mongoid and other ORMs, but it seems it uses limit
  ; and skip (offset), not range-based pagination
  ; https://github.com/amatsuda/kaminari
  [{:keys [database entity] :as model-component}
   {where :where sort-order :sort}
   start
   page-size]
  ;
  ; MongoDB uses a map to specify order
  ; http://docs.mongodb.org/manual/reference/method/cursor.sort/
  ; Given that JavaScript maps don't guarantee any order, and thinking of
  ; ordering on multiple fields, why this syntax?
  ; http://docs.mongodb.org/manual/reference/method/cursor.sort/
  ; https://github.com/aboekhoff/congomongo/issues/100
  ; Monger uses array maps (ultimately CongoMongo too [1])
  ; http://clojuremongodb.info/articles/querying.html#sorting_skip_and_limit
  ; array map will only maintain sort order when un-'modified'.
  ; http://clojure.org/data_structures#Data Structures-ArrayMaps
  ; How to get a clojure array-map to maintain insertion order after assoc?
  ; http://stackoverflow.com/q/12034142
  ;
  ; [1] see coerce-ordered-fields
  ; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo/coerce.clj
  ;
  (let [full-sort-order
          ; #TODO what happens when a explicit {:_id 1} sort order is specified? And if it's {:_id -1}?
          ; sort-order should be an array-map, and this adds `:_id 1` to the
          ; end of this array-map . It doesn't use
          ;   (into sort-order {:_id 1})
          ; because possibly that counts as a "modified" array map, which may
          ; not maintain the sort order.
          ; http://clojure.org/data_structures#Data Structures-ArrayMaps
          (apply array-map (flatten (conj (vec sort-order) [:_id 1])))

        paging-conditions
          (generate-paging-conditions full-sort-order start)


        records-batch
          ; Notice that...
          ; `where` may be empty, when paging through all the records of the collection
          ; 'paging-conditions` may be empty, when the first page has to be served
          ; ... but merge-conditions-with-and will do the right thing in any case
          (find model-component
                {:where (merge-conditions-with-and where paging-conditions)
                 :sort full-sort-order
                 :limit (inc page-size)})

        next-page-start
          (when (> (clojure.core/count records-batch) page-size)
                (select-keys (last records-batch) (keys full-sort-order)))
        page-records
          (if next-page-start (butlast records-batch) records-batch)


        ; (array-map :updated_at -1 :_id 1)
        ; =>
        ; (array-map :updated_at 1 :_id -1)
        ; The index will support the reverse order, see
        ; http://docs.mongodb.org/manual/core/index-compound/#sort-order
        ; http://guides.rubyonrails.org/active_record_querying.html#reverse-order
        reverse-sort-order
          (apply array-map
                 (flatten (map (fn [[field order]] [field (* -1 order)])
                               full-sort-order)))

        ;;     ; [{:sort_field_1 {:$gte (:sort_field_1 start)}}
        ;;     ;  {:sort_field_2 {:$lte (:sort_field_2 start)}}
        ;;     ;  ...
        ;;     ;  {:_id {:$gte {:_id start}}}]
        ;;     ;
        ;;     ; =>
        ;;     ;
        ;;     ; [{:sort_field_1 {:$lte (:sort_field_1 start)}}
        ;;     ;  {:sort_field_2 {:$gte (:sort_field_2 start)}}
        ;;     ;  ...
        ;;     ;  {:_id {:$gte {:_id start}}}]
        prev-page-paging-conditions
          (generate-paging-conditions reverse-sort-order start)
        prev-page-records-batch
          (when (seq start)
                (find model-component
                      {:where (merge-conditions-with-and where prev-page-paging-conditions)
                       :sort reverse-sort-order
                       :limit (inc page-size)}))
        prev-page-start
          (when (seq start)
                (when (> (clojure.core/count prev-page-records-batch) 1)
                      (select-keys (last prev-page-records-batch) (keys full-sort-order))))

        ]


    ;;

    (log/debug "full-sort-order: " full-sort-order)
    (log/debug "reverse-sort-order: " reverse-sort-order)
    (log/debug "paging-conditions: " paging-conditions)
    (log/debug "prev-page-paging-conditions: " prev-page-paging-conditions)
    (log/debug "count prev-page-records-batch: " (clojure.core/count prev-page-records-batch))

    ; this structure represents a page (it can be seen as equivalent to
    ; Django's Page object
    ; https://docs.djangoproject.com/en/1.5/topics/pagination/#page-objects )
    {:items page-records
     :previous-page-start prev-page-start
     :next-page-start next-page-start}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; ???

; The uniqueness check is different depending on if the record is new or not.
; Similar to Rails uniqueness validation
; http://guides.rubyonrails.org/active_record_validations.html#uniqueness
; http://api.rubyonrails.org/classes/ActiveRecord/Validations/ClassMethods.html#method-i-validates_uniqueness_of
; See also Sequel's validates_unique
; http://sequel.jeremyevans.net/rdoc/files/doc/validations_rdoc.html#label-validates_unique
; http://en.wikipedia.org/wiki/Unique_key
(defn unique?
  "Checks that there are no other records in the database with the same
  values for the combination of fields specified in unique-key-fields.
  The uniqueness constraint can be scoped to records matching
  scope-conditions (the default scope is the whole database). Ex.

      (unique? book-component
               book-record
               [:title :author_id]
               {:status \"active\"})

  If the record's attributes contain an :_id, it's assumed that these
  attributes correspond to an existing record and the uniqueness check will
  not take into account any record with that same :_id (so it will not take
  itself into account)."
  ; Notice that scope-conditions doesn't have to do with the Rails'
  ; validate_uniqueness_of :scope parameter, but with the :conditions
  ; parameter
  ; http://api.rubyonrails.org/classes/ActiveRecord/Validations/ClassMethods.html#method-i-validates_uniqueness_of
  ; I think the :scope parameter name in Rails is confusing. Others probably
  ; think the same: Sequel uses the term "scope" in the context of its
  ; validate_unique in the same sense as it's used here
  ; http://sequel.jeremyevans.net/rdoc/files/doc/validations_rdoc.html#label-validates_unique
  ;
  [model-component attributes unique-key-fields & [scope-conditions]]
  (let [_id
          (:_id attributes)
        unique-fields-conditions
          ; if attributes is {:author_id 123}
          ; unique-key-fields is [:title :author_id]
          ; =>
          ; {:title nil :author_id 123}
          ; http://stackoverflow.com/a/5543309
          ;
          ; Notice that in MongoDB a query like { title: null } matches
          ; documents that either contain the 'title' field whose value is
          ; null or that do not contain the title field.
          ; http://docs.mongodb.org/manual/faq/developers/#faq-developers-query-for-nulls
          (merge (zipmap unique-key-fields (repeat nil))
                 (select-keys attributes unique-key-fields))]
    ; In the most nested merge, if
    ; attributes is {:author_id 123}
    ; unique-key-fields is [:title :author_id]
    ; =>
    ; {:title nil :author_id 123}
    ; http://stackoverflow.com/a/5543309
    ;
    ; Notice that in MongoDB a query like { title: null } matches documents
    ; that either contain the 'title' field whose value is null or that do
    ; not contain the title field.
    ; http://docs.mongodb.org/manual/faq/developers/#faq-developers-query-for-nulls
    (not (find-one
           model-component
           (merge-conditions-with-and
             (merge unique-fields-conditions
                    (when _id {:_id {:$ne (to-object-id _id)}}))
             scope-conditions)))))

(defn update-all
  "Applies the specified `updates` to all the records that match the
  `conditions`. The `updates` have to specify the update operator to use
  ($set, $unset...).

  Lifecycle callbacks and validations are not triggered.

  The `conditions` MUST be supplied, to prevent the accidental update of all
  the records in a collection. If that's really what's desired, an empty map
  can be passed as the 'conditions' parameter.

  Example:
    (update-all product-component
                {:$set {:name \"changed\"}}
                {:name \"test name\"})"
  ; Similar to ActiveRecord's update_all(updates, conditions = nil, options = {})
  ; http://api.rubyonrails.org/classes/ActiveRecord/Relation.html#method-i-update_all
  ; but here, contrary to Rails, the conditions MUST be supplied.
  ; See also Mongoid's update_all
  ; http://mongoid.org/en/mongoid/docs/querying.html#query_plus
  ;
  ; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
  ; CongoMongo calls
  ; http://api.mongodb.org/java/current/com/mongodb/DBCollection.html#update(com.mongodb.DBObject, com.mongodb.DBObject, boolean, boolean, com.mongodb.WriteConcern)
  ; which corresponds to this in the mongo shell interface
  ; http://docs.mongodb.org/manual/core/write-operations-introduction/#update
  [{:keys [database entity] :as model-component} updates conditions]
  (db/with-mongo (:connection database)
    (db/update! (:collection entity) conditions updates :multiple true)))

; Mongologic, like Rails' ActiveRecord, allows to hook more than one
; function into the same slot in the record's life cycle.
; http://books.google.es/books?id=slwLAqkT_Y0C&lpg=PT397&ots=9b2wBJjAxO&dq=callback%20queue%20rails&pg=PT397#v=onepage&q=callback%20queue%20rails&f=false
; (For the moment, Mongologic only allows this for :after-update)
(defn- compose-callback-fns
  [callback-fns model-component original-record]
  (let [callback-fns
          ;; http://stackoverflow.com/a/11782628
          (if (instance? clojure.lang.Atom callback-fns)
              (deref callback-fns)
              callback-fns)]
    (when callback-fns
      ;; The use of anonymous functions below (`fn`) solves the problem of
      ;; argument order when using partial, as is also explained in
      ;; https://kotka.de/blog/2010/08/Did_you_know_VII.html
      (->> (if (sequential? callback-fns) callback-fns [callback-fns])
           (map (fn [f]
                  (fn [changed-record]
                    (f model-component changed-record original-record))))
           reverse
           (apply comp)))))

;; #TODO #DRY See if compose-callback-fns can be generalized to handle this too
(defn- compose-delete-callback-fns
  [callback-fns entity]
  (let [callback-fns
          ;; http://stackoverflow.com/a/11782628
          (if (instance? clojure.lang.Atom callback-fns)
              (deref callback-fns)
              callback-fns)]
    (when callback-fns
      (->> (if (sequential? callback-fns) callback-fns [callback-fns])
           (map (fn [f]
                  (fn [record]
                    (f entity record))))
           reverse
           (apply comp)))))

;; In Ruby on Rails, when an Active Record callback is provided as an
;; object encapsulating the method that implements the actual callback,
;; this method, when called, will receive the model object as a parameter.
;; http://guides.rubyonrails.org/active_record_callbacks.html#callback-classes
;; This is sometimes used to query the database, like in this example...
;; http://guides.rubyonrails.org/active_record_callbacks.html#multiple-conditions-for-callbacks
;;
;; In Mongologic, besides the model object, the model's entity will also be
;; passed to these callbacks, so that they have the necessary info (ex. the
;; collection name) to use the database if they need to. Notice that in Rails
;; this info is passed implicitly through the model object.

;; Same callback calling order as in Rails
;; http://guides.rubyonrails.org/active_record_callbacks.html#available-callbacks

;; About deleting fields in the same update as others are set (see
;; docstring), and as an aside, this doesn't seem to be possible in Mongoid
;; for Ruby for example. It would be necessary to use the lower level Moped
;; driver, but then validations and timestamping benefits would be lost,
;; besides being more verbose.
;; http://mongoid.org/en/mongoid/docs/persistence.html

;; Keyword params are NOT used for the optional hash param, so the way to call
;; this is...
;;   (update user-entity 23 {:username \"user1\"} {:skip-validations true})
;; not...
;;   (update user-entity 23 {:username \"user1\"} :skip-validations true)
;; http://stackoverflow.com/questions/717963/clojure-keyword-arguments

(defn update
  "If the record resulting from the update is valid, it timestamps and saves
  the updated record. If there are no changes, nothing is saved.
  It's similar to ActiveRecord's update(id, attributes)
  http://api.rubyonrails.org/classes/ActiveRecord/Relation.html#method-i-update

  Only the $unset \"update operator\" is supported.
  http://docs.mongodb.org/manual/reference/operators/#update

  $unset allows to delete fields. To delete a field, specify :$unset as its
  new value. Example:
#TODO Fix example, it should be passed a component
    (update {:collection :users}
            23
            {:password \"s3cret\" :password_reset_code :$unset})

  Notice that it's possible to delete fields in the same update as others are
  set.

  These callbacks will be called, in the order listed here, if defined in
  the map under the :entity key of the `model-component` parameter:
  - :before-validation
  - :validator
  - :before-save
  - :before-update
  - :on-update-errors
  - :after-update

  All callbacks will be passed the `model-component` and the entire record
  with the corresponding attributes updated. The :validator callback must
  return a collection of errors (empty or nil if no errors), the other
  callbacks must return the entire record, maybe with some attributes
  changed, added, or deleted.

  For :on-update-errors to work, WriteConcern.ACKNOWLEDGED (vs
  UNACKNOWLEDGED) must be used
  (`(set-write-concern *mongo-config* :acknowledged)`), as otherwise errors
  don't cause exceptions, and (currently) Mongologic only calls this callback
  if an exception occurs.

  Returns:
    - [false validation-errors] if validations fail (where validation-errors
    is what the validator returned)
    - [false nil] if no record is found with the specified id, or the update
    fails
    - [true updated-object] otherwise"
  [{:keys [database entity] :as model-component}
   id
   attributes
   & [{:keys [skip-validations] :or {skip-validations false}}]]
  (let [validate
          (:validator entity)
        empty-callback-fn
          (fn [e r] r)
        before-validation-hook
          (or (:before-validation entity) empty-callback-fn)
        old-record
          (find-by-id model-component id)
        ; http://stackoverflow.com/questions/2753874/how-to-filter-a-persistent-map-in-clojure
        ; http://clojuredocs.org/clojure_core/clojure.core/for
        ; http://docs.mongodb.org/manual/reference/operators/#_S_unset
        unset-map
          (select-keys attributes (for [[k v] attributes
                                        :when (= v :$unset)]
                                    k))
        ;
        old-record-without-deleted-fields
          (apply dissoc old-record (keys unset-map))
        attributes-without-deleted-ones
          (apply dissoc attributes (keys unset-map))
        changed-record
          (merge old-record-without-deleted-fields
                 attributes-without-deleted-ones
                 ; Sets the value of _id, in case it's not set, or
                 ; incorrectly set as a String (instead of an ObjectId).
                 ; Any previous value can be safely overridden as MongoDB
                 ; doesn't allow to modify an _id on an update operation
                 ; anyway [1][2].
                 ;
                 ; This is done because callbacks, which will receive this
                 ; updated record as a parameter, may fairly rely on the
                 ; presence of this _id attribute. As a reference, notice
                 ; that in Rails update callbacks can access the `id` on
                 ; the object through which they are called.
                 ;
                 ; [1] http://stackoverflow.com/q/4012855
                 ; [2] Causes a
                 ; #<MongoException com.mongodb.MongoException: Mod on _id
                 ; not allowed>
                 {:_id (:_id old-record)})
        ; This should also work, but I like the code above better...
        ; merged-attrs (merge old-record attributes)
        ; changed-record (select-keys merged-attrs
        ;                             (for [[k v] merged-attrs
        ;                                   :when (not= v :$unset)]
        ;                                  k))
        changed-record
          (before-validation-hook model-component changed-record)
        validation-errors
          (when-not skip-validations
            (and validate (validate model-component changed-record)))]
    (if (or (nil? old-record) (seq validation-errors))
      [false validation-errors]
      ; CongoMongo's update! returns a WriteResult object from the
      ; underlying Java driver
      ; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
      ; https://github.com/mongodb/mongo-java-driver/blob/master/src/main/com/mongodb/DBCollection.java
      ;
      ; Notice that if "write concern" were not set to :safe (see
      ; models.clj), MongoDB Java driver would never raise any exception.

      ; Updates and timestamps the record ONLY IF there are changes,
      ; like Rails
      ; https://github.com/rails/rails/blob/master/activerecord/lib/active_record/timestamp.rb
      ;
      (let [before-save-fn (or (:before-save entity) empty-callback-fn)
            prepared-record (before-save-fn model-component changed-record)]
        ;; Note that, because of how prepared-record is obtained, here it has
        ;; the same value for :updated_at as old-record (unless a different
        ;; one was specified in the `attributes` param). This allows to
        ;; determine if the record has to be actually updated using the
        ;; condition below. (If it has, updated_at will be properly set
        ;; later.)
        (if (= prepared-record old-record)  ;; #TODO AND (empty? unset-map) ?

            [true old-record]

            (let [changed-record  ; overrides the outer changed-record,
                    ; http://books.google.es/books?id=nZTvSa4KqfQC&lpg=PA28&ots=0V4mAN8Ovk&dq=clojure%20nesting%20let%20%20same%20name&pg=PA28
                    (if-let [before-update-fn (:before-update entity)]
                      (before-update-fn model-component prepared-record)
                      prepared-record)
                  updated-at
                    ; If :updated_at is specified and it's different than
                    ; the old one, then that will be used instead of the
                    ; current timestamp, like in Rails. The only problem,
                    ; like in Rails, is that it's not possible to change
                    ; the value of an attribute without changing the
                    ; timestamp (unless it's done in two operations:
                    ; first the attribute is updated, then a second
                    ; update is used to replace the new timestamp with
                    ; the old one).
                    (if (and (contains? prepared-record :updated_at)
                             (not= (:updated_at prepared-record)
                                   (:updated_at old-record)))
                        (:updated_at prepared-record)
                        (time/now))
                  modifications
                    ;; Since MongoDB 2.5, modifiers like $set or $unset
                    ;; can't be empty
                    ;; https://jira.mongodb.org/browse/SERVER-12266
                    (merge ; _id cannot be modified
                           ; http://stackoverflow.com/q/4012855
                           {:$set (assoc (dissoc changed-record :_id)
                                         :updated_at updated-at)}
                           (when (seq unset-map) {:$unset unset-map}))
                  updated-record
                    (try
                      ; The second argument of MongoDB's update (the 3rd in
                      ; CongoMongo) is the "updated object or $ operators
                      ; (e.g., $inc) which manipulate the object"
                      ; http://www.mongodb.org/display/DOCS/Updating#Updating-update%28%29
                      ; Notice that an "updated object" represents a whole
                      ; document, not only the fields that have to be modified.
                      ; This updated object will completely replace the old
                      ; object. To modify only some fields, the $ modifiers have
                      ; to be used.
                      ;
                      (db/with-mongo (:connection database)
                        (db/update! (:collection entity)
                                    old-record modifications))
                      ; CongoMongo's update! returns a WriteResult
                      ; https://github.com/aboekhoff/congomongo#update
                      ; but the updated record has to be returned, that's why
                      ; it has to retrieved from the database
                      (find-by-id model-component id)
                      (catch Exception e
                        (log/info (str "log-message=\"in update\" exception="
                                       e " collection=" (:collection entity)
                                       " old-record=" old-record
                                       " modifications=" modifications))
                        ; :on-update-errors is similar to Rails'
                        ; after_rollback, but not exactly because MongoDB
                        ; doesn't have transactions
                        ; http://guides.rubyonrails.org/active_record_callbacks.html#transaction-callbacks
                        (when-let [on-update-errors-fn (:on-update-errors entity)]
                          (on-update-errors-fn model-component changed-record))
                        nil))]
              ; If there were exceptions on update then :after-update
              ; callbacks will not be called. This is like in Rails, where
              ; exceptions halt the execution chain
              ; http://guides.rubyonrails.org/active_record_callbacks.html#halting-execution
              ; http://stackoverflow.com/questions/12241244/rails-exception-in-after-create-stopping-save
              (if updated-record
                (if-let [composed-after-update-fn
                           (compose-callback-fns (:after-update entity)
                                                 model-component
                                                 old-record)]
                  [true (composed-after-update-fn updated-record)]
                  [true updated-record])
                [false {:base [:update-error]}])))))))

; Named after the equivalent Rails method
; http://api.rubyonrails.org/classes/ActiveRecord/Relation.html#method-i-delete_all
(defn delete-all
  "Returns:
     - the number of records deleted"
  [{:keys [database entity] :as model-component} query]
  (let [result
          (db/with-mongo (:connection database)
            (db/destroy! (:collection entity) query))]
    ;; WriteResult changed in version 2.6 but MongoLab is still running mongod 2.4.9 (2014-11-16)
    ;; http://docs.mongodb.org/manual/reference/method/db.collection.remove/#writeresult
    ;; According to that shell command documentation, WriteResult should
    ;; contain the number of documents removed in "nRemoved", but it seems
    ;; that's shell-specific, because Java driver's WriteResult doesn't have
    ;; anything about "nRemoved", but instead
    ;; http://api.mongodb.org/java/current/com/mongodb/WriteResult.html#getN()
    (.getN result)))

(defn delete
  ; Named after the equivalent Rails method
  ; http://api.rubyonrails.org/classes/ActiveRecord/Relation.html#method-i-delete
  "If the id parameter is a String, it's automatically converted to an
  ObjectId.

  These callbacks will be called, in the order listed here, if defined in
  the `entity` parameter:
  - :before-delete
  - :after-delete

  Returns:
    - the number of records deleted"
  [{:keys [database entity] :as model-component} id]
  ;
  ; #TODO
  ; Eventually, a way for the :after-delete callback to check if the record
  ; has just been deleted may be necessary, something like Rails' `destroyed?`
  ; http://stackoverflow.com/q/1297111
  ; http://api.rubyonrails.org/classes/ActiveRecord/Persistence.html#method-i-destroyed-3F
  ;
  ; #TODO
  ; 2014-11-16: Review the implementation below, maybe destroy! actually
  ; allows to know if anything was deleted, see `delete-all` above.
  ;
  ; CongoMongo's destroy! function (which uses the remove method of
  ; MongoDB's Java driver) is the most obvious way to delete a document but
  ; it doesn't allow to know if anything was deleted. Actually, it does, but
  ; only if the "write concern" is set to :safe, and by inspecting the
  ; WriteResult Java object that it returns.
  ;
  ; Because of this, the more general command function is used, as it allows
  ; to know the number of documents deleted independently of the
  ; "write concern", and it doesn't require messing with Java.
  ;
  ; If nothing was deleted, there's no :lastErrorObject, otherwise it contains
  ; an :n element with the number of documents deleted.
  ;   http://www.mongodb.org/display/DOCS/getLastError+Command
  ;
  ; CongoMongo provides a fetch-and-modify function that wraps MongoDB's
  ; findAndModify command, but I don't see the value of it, and I prefer to
  ; use the generic command function.
  ; "The findAndModify command modifies and returns a single document."
  ; http://docs.mongodb.org/manual/reference/command/findAndModify/
  (let [collection (:collection entity)
        id (if (string? id) (to-object-id id) id)
        record (find-by-id model-component id)
        _
          (when-let [before-delete-fn (:before-delete entity)]
            (before-delete-fn model-component record))
        command-result
          (db/with-mongo (:connection database)
            ;; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
            ;; http://api.mongodb.org/java/current/com/mongodb/DB.html#command(com.mongodb.DBObject)
            (db/command {:findAndModify (name collection)
                         :query {:_id id}
                         :remove true}))]
    ; Considered to support an :on-delete-errors callback. In Ruby on Rails
    ; this would be handled in a generic after_rollback callback.
    ; Notice that if the record to delete is not found, that's not an error.
    ; http://stackoverflow.com/q/14985478
    ; But in a transactional database, although not in MongoDB, a DELETE
    ; could fail because of a foreign key constraint.
    ; Tried to make the delete fail by specifying a non-existing collection,
    ; but the result was the same as when the record to delete is not found.
    ; {:serverUsed "ds029267.mongolab.com/23.22.107.129:29267",
    ;  :value nil,
    ;  :ok 1.0}

    ; In an unrelated note, if an equivalent to Rails' after_remove [^1]
    ; callback is ever considered, be aware of this behavior in Rails...
    ; "after_remove callback is run even though the record was not actually
    ; removed."
    ; https://github.com/rails/rails/pull/9346
    ; Actually it seems that is also true for after_destroy...
    ; "In general I think it would be correct to make this general behavior
    ; so that after_destroy callbacks are not called if no record was
    ; deleted."
    ; https://groups.google.com/forum/#!topic/rubyonrails-core/cl20eykvNsQ
    ; That thread is from 2012, I don't understand why there's no mention
    ; about `destroyed?` (see TODO at the start of this function).
    ;
    ; [^1]: after_remove is different than after_destroy
    ; http://guides.rubyonrails.org/association_basics.html#association-callbacks
    ; http://guides.rubyonrails.org/active_record_callbacks.html#destroying-an-object


    (when-let [composed-after-delete-fn
                 (compose-delete-callback-fns (:after-delete entity)
                                              model-component)]
      (composed-after-delete-fn record))

    (or (get-in command-result [:lastErrorObject :n]) 0)))


; In MySQL, DATE() can be used to extract the date part of a datetime
; http://dev.mysql.com/doc/refman/5.7/en/date-and-time-functions.html#function_date
; In PostgreSQL there's DATE_TRUNC()
; http://www.postgresql.org/docs/9.3/static/functions-datetime.html#FUNCTIONS-DATETIME-TRUNC
; In MongoDB there's no equivalent function.
;
; In Rails there's beginning_of_day()
; http://api.rubyonrails.org/classes/DateTime.html#method-i-beginning_of_day
;
; JavaScript's getTimezoneOffset() returns the offset in minutes
; https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/getTimezoneOffset
; Rails' utc_offset() returns the offset in seconds
; http://api.rubyonrails.org/classes/DateTime.html#method-i-utc_offset
; MongoDB's $add operator expects milliseconds
; https://jira.mongodb.org/browse/SERVER-6239
(defn beginning-of-day
  "Returns a map, to be used in the context of CongoMongo's interface to
  MongoDB's aggregation framework, to get a Date value with the same day as
  the one resulting from adding utc-offset-in-milliseconds to the specified
  date-field (expected as a keyword, like in :field-name), but with a time of
  0:00 .

  #TODO Provide an example

  http://docs.mongodb.org/manual/core/aggregation-pipeline/
  https://github.com/aboekhoff/congomongo#aggregation-requires-mongodb-22-or-later
  "
  [date-field utc-offset-in-milliseconds]
  ; Ideally, a time-zone would be specified instead of
  ; utc-offset-in-milliseconds. Then the datetime stored in the database as
  ; UTC could be converted to local time with that time zone, taking into
  ; account daylight saving time. Unfortunately, as of version 2.4 of
  ; MongoDB, it seems there's no way to convert a date to a given time zone
  ; in the context of the aggregation framework.
  ;
  ; http://www.kamsky.org/1/post/2013/03/stupid-date-tricks-with-aggregation-framework.html
  ; http://docs.mongodb.org/manual/reference/aggregation/operator-nav/
  (let [field (str "$" (name date-field))
        offset-date-time {:$add [field utc-offset-in-milliseconds]}
        ; the wrapping of $add in an array is a temporary work around to a
        ; MongoDB bug that should be fixed in version 2.6
        ; https://jira.mongodb.org/browse/SERVER-6310?focusedCommentId=431343&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-431343
        ; http://stackoverflow.com/questions/5082850/whats-the-convention-for-using-an-asterisk-at-the-end-of-a-function-name-in-clo
        offset-date-time* [offset-date-time]]
    {:$subtract [offset-date-time
                 {:$add [{:$millisecond offset-date-time*}
                         {:$multiply [{:$second offset-date-time*}
                                      1000]}
                         {:$multiply [{:$minute offset-date-time*}
                                      60
                                      1000]}
                         {:$multiply [{:$hour offset-date-time*}
                                      60
                                      60
                                      1000]}]}]}))


(defn to-object-id
  "Returns the MongoDB ObjectId corresponding to id, which may be the
  hexadecimal string value of the ObjectId, or the ObjectId itself.
  It may raise an exception if the id is not a valid ObjectId (ex. if it's
  the empty string, or nil)."
  [id]
  ;; The object-id function raises an exception when passed an ObjectId as a
  ;; parameter. The code below works around this by first converting to a
  ;; string. This allows the function to work for both String and ObjectId
  ;; input params.
  ;; https://github.com/aboekhoff/congomongo/issues/77
  ;; http://docs.mongodb.org/manual/reference/object-id/
  (db/object-id (str id)))
