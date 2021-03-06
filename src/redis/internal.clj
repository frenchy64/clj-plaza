(ns redis.internal
  (:use redis.utils)
  (:refer-clojure :exclude [send read read-line])
  (:import [java.io Reader BufferedReader InputStreamReader StringReader]
           [java.net Socket]
           [org.apache.commons.pool.impl GenericObjectPool]
           [org.apache.commons.pool BasePoolableObjectFactory]))

(set! *warn-on-reflection* true)

(def *cr*  0x0d)
(def *lf*  0x0a)
(defn- cr? [c] (= c *cr*))
(defn- lf? [c] (= c *lf*))

(defstruct connection
  :host :port :password :db :timeout :socket :reader :writer)

(def *connection* (struct-map connection
                    :host     "127.0.0.1"
                    :port     6379
                    :password nil
                    :db       0
                    :timeout  5000
                    :socket   nil
                    :reader   nil
                    :writer   nil))

(defn socket* []
  (or (:socket *connection*)
      (throw (Exception. "Not connected to a Redis server"))))

(defn send-command
  "Send a command string to server"
  [#^String cmd]
  (let [out (.getOutputStream (#^Socket socket*))
        bytes (.getBytes cmd)]
    (.write out bytes)))

(defn- uppercase [#^String s] (.toUpperCase s))
(defn- trim [#^String s] (.trim s))
(defn- parse-int [#^String s] (Integer/parseInt s))
;(defn- char-array [len] (make-array Character/TYPE len))

(defn read-crlf
  "Read a CR+LF combination from Reader"
  [#^Reader reader]
  (let [cr (.read reader)
        lf (.read reader)]
    (when-not
        (and (cr? cr)
             (lf? lf))
      (throw (Exception. "Error reading CR/LF")))
    nil))

(defn read-line-crlf
  "Read from reader until exactly a CR+LF combination is
  found. Returns the line read without trailing CR+LF.

  This is used instead of Reader.readLine() method since that method
  tries to read either a CR, a LF or a CR+LF, which we don't want in
  this case."
  [#^BufferedReader reader]
  (loop [line []
         c (.read reader)]
    (when (< c 0)
      (throw (Exception. "Error reading line: EOF reached before CR/LF sequence")))
    (if (cr? c)
      (let [next (.read reader)]
        (if (lf? next)
          (apply str line)
          (throw (Exception. "Error reading line: Missing LF"))))
      (recur (conj line (char c))
             (.read reader)))))

;;
;; Reply dispatching
;;
(defn- do-read [#^Reader reader #^chars cbuf offset length]
  (let [nread (.read reader cbuf offset length)]
    (if (not= nread length)
      (recur reader cbuf (+ offset nread) (- length nread)))))

(defn reply-type
  ([#^BufferedReader reader]
     (char (.read reader))))

(defmulti parse-reply reply-type :default :unknown)

(defn read-reply
  ([]
     (let [reader (*connection* :reader)]
       (read-reply reader)))
  ([#^BufferedReader reader]
     (parse-reply reader)))

(defmethod parse-reply :unknown
  [#^BufferedReader reader]
  (throw (Exception. (str "Unknown reply type:"))))

(defmethod parse-reply \-
  [#^BufferedReader reader]
  (let [error (read-line-crlf reader)]
    (throw (Exception. (str "Server error: " error)))))

(defmethod parse-reply \+
  [#^BufferedReader reader]
  (read-line-crlf reader))

(defmethod parse-reply \$
  [#^BufferedReader reader]
  (let [line (read-line-crlf reader)
        length (parse-int line)]
    (if (< length 0)
      nil
      (let [#^chars cbuf (char-array length)]
        (do
          (do-read reader cbuf 0 length)
          (read-crlf reader) ;; CRLF
          (String. cbuf))))))

(defmethod parse-reply \*
  [#^BufferedReader reader]
  (let [line (read-line-crlf reader)
        count (parse-int line)]
    (if (< count 0)
      nil
      (loop [i count
             replies []]
        (if (zero? i)
          replies
          (recur (dec i) (conj replies (read-reply reader))))))))

(defmethod parse-reply \:
  [#^BufferedReader reader]
  (let [line (trim (read-line-crlf reader))
        int (parse-int line)]
    int))

;;
;; Command functions
;;
(defn- str-join
  "Join elements in sequence with separator"
  [separator sequence]
  (apply str (interpose separator sequence)))


(defn inline-command
  "Create a string for an inline command"
  [name & args]
  (let [cmd (str-join " " (conj args name))]
    (str cmd "\r\n")))

(defn bulk-command
  "Create a string for a bulk command"
  [name & args]
  (let [data (str (last args))
        data-length (count (str data))
        args* (concat (butlast args) [data-length])
        cmd (apply inline-command name args*)]
    (str cmd data "\r\n")))

(defn- sort-command-args-to-string
  [args]
  (loop [arg-strings []
         args args]
    (if (empty? args)
      (str-join " " arg-strings)
      (let [type (first args)
            args (rest args)]
        (condp = type
          :by (let [pattern (first args)]
                (recur (conj arg-strings "BY" pattern)
                       (rest args)))
          :limit (let [start (first args)
                       end (second args)]
                   (recur (conj arg-strings "LIMIT" start end)
                          (drop 2 args)))
          :get (let [pattern (first args)]
                 (recur (conj arg-strings "GET" pattern)
                        (rest args)))
          :store (let [key (first args)]
                   (recur (conj arg-strings "STORE" key)
                          (rest args)))
          :alpha (recur (conj arg-strings "ALPHA") args)
          :asc  (recur (conj arg-strings "ASC") args)
          :desc (recur (conj arg-strings "DESC") args)
          (throw (Exception. (str "Error parsing SORT arguments: Unknown argument: " type))))))))

(defn sort-command
  [name & args]
  (when-not (= name "SORT")
    (throw (Exception. "Sort command name must be 'SORT'")))
  (let [key (first args)
        arg-string (sort-command-args-to-string (rest args))
        cmd (str "SORT " key)]
    (if (empty? arg-string)
      (str cmd "\r\n")
      (str cmd " " arg-string "\r\n"))))

(def command-fns {:inline 'inline-command
                  :bulk   'bulk-command
                  :sort   'sort-command})

(defn parse-params
  "Return a restructuring of params, which is of form:
     [arg* (& more)?]
  into
     [(arg1 arg2 ..) more]"
  [params]
  (let [[args rest] (split-with #(not= % '&) params)]
    [args (last rest)]))

(defmacro defcommand
  "Define a function for Redis command name with parameters
  params. Type is one of :inline, :bulk or :sort, which determines how
  the command string is constructued."
  ([name params type] `(defcommand ~name ~params ~type (fn [reply#] reply#)))
  ([name params type reply-fn] `(~name ~params ~type ~reply-fn)
     (do
       (let [command (uppercase (str name))
             command-fn (type command-fns)
             [command-params
              command-params-rest] (parse-params params)]
         `(defn ~name
            ~params
            (let [request# (apply ~command-fn
                                  ~command
                                  ~@command-params
                                  ~command-params-rest)]
              (send-command request#)
              (~reply-fn (read-reply))))))))

(defmacro defcommands
  [& command-defs]
  `(do ~@(map (fn [command-def]
              `(defcommand ~@command-def)) command-defs)))

;;
;; connection pooling
;;
(def *pool* (atom nil))

(defn connect-to-server
  "Create a Socket connected to server"
  [server]
  (let [{:keys [host port timeout]} server
        socket (Socket. #^String host #^Integer port)]
    (doto socket
      (.setTcpNoDelay true)
      (.setKeepAlive true))))

(defn new-redis-connection [server-spec]
  (let [connection (merge *connection* server-spec)
        #^Socket socket (connect-to-server connection)
        input-stream (.getInputStream socket)
        output-stream (.getOutputStream socket)
        reader (BufferedReader. (InputStreamReader. input-stream))]
    (assoc connection
      :socket socket
      :reader reader
      :created-at (System/currentTimeMillis))))

(defn connection-valid? []
  (= "PONG" (do (send-command (inline-command "PING"))
                (read-reply))))

(defn connection-factory [server-spec]
  (proxy [BasePoolableObjectFactory] []
    (makeObject []
      (new-redis-connection server-spec))
    (validateObject [c]
                    (binding [*connection* c]
                      (connection-valid?)))
    (destroyObject [c]
      (.close #^Socket (:socket c)))))

(defrunonce init-pool [server-spec]
  (let [factory (connection-factory server-spec)
        p (doto (GenericObjectPool. factory)
               (.setMaxActive 20)
               (.setLifo false)
               (.setTimeBetweenEvictionRunsMillis 30000)
               (.setWhenExhaustedAction GenericObjectPool/WHEN_EXHAUSTED_BLOCK)
               (.setTestWhileIdle true))]
    (reset! *pool* p)))

(defn get-connection-from-pool [server-spec]
  (init-pool server-spec)
  (.borrowObject #^GenericObjectPool @*pool*))

(defn return-connection-to-pool [c]
  (.returnObject #^GenericObjectPool @*pool* c))

(defn with-server* [server-spec func]
  (binding [*connection* (get-connection-from-pool server-spec)]
    (let [ret (func)]
      (return-connection-to-pool *connection*)
      ret)))
