;; @author Antonio Garote
;; @email antoniogarrote@gmail.com
;; @date 29.04.2010

(ns plaza.utils)

(defn keyword-to-string
  "transforms a keyword into a string"
  ([k]
     (if (nil? k) ""
         (if (= (class k) String)
           k
           (let [sk (str k)]
             (.substring sk 1))))))

(defn fold-list
  "Transforms a list: [1 2 3 4] -> [[1 2] [3 4]]"
  ([c]
     (loop [xs c
            odd 0
            tmp []
            acum []]
       (if (empty? xs)
         acum
         (let [x (first xs)]
           (if (= odd 1)
             (recur (rest xs)
                    0
                    []
                    (conj acum (conj tmp x)))
             (recur (rest xs)
                    1
                    (conj tmp x)
                    acum)))))))

(defn show-java-methods
  ([obj]
     (let [ms (.. obj getClass getDeclaredMethods)
           max (alength ms)]
       (loop [count 0]
         (when (< count max)
           (let [m (aget ms count)
                 params (.getParameterTypes m)
                 params-max (alength params)
                 return-type (.getReturnType m)
                 to-show (str (loop [acum (str (.getName m) "(")
                                     params-count 0]
                                (if (< params-count params-max)
                                  (recur (str acum " " (aget params params-count))
                                         (+ params-count 1))
                                  acum))
                              " ) : " return-type)]
             (println (str to-show))
             (recur (+ 1 count))))))))

(defn collect-java-implemented-interfaces
  ([obj]
     (let [is (.. obj getClass getInterfaces)
           max (alength is)]
       (loop [count 0
              acum []]
         (if (< count max)
           (recur (+ count 1)
                  (conj acum (aget is count)))
           acum)))))

(defn flatten-1
  ([seq]
     (let [old-meta (meta seq)
           ts-pre (reduce (fn [acum item]
                            (if (and (coll? item) (coll? (first item)))
                              (concat acum item)
                              (conj acum item)))
                          []
                          seq)
           ts (vec (distinct ts-pre))]
       (with-meta ts old-meta))))

;; (grab-document-url "http://hamish.blogs.com/mishmash/bib-take1.xml")
(defn grab-document-url
  "Retrieves an input stream from a remote URL"
  ([url]
     (let [url (java.net.URL. url)
           conn (.openConnection url)]
       (.getInputStream conn))))

(defn probe-agent!
  ([agent error-text should-clean]
     (loop []
       (let [res (await-for 200 agent)]
         (if (nil? res)
           (if (agent-errors agent)
             (do (when should-clean
                   (clear-agent-errors agent))
                 (throw (Exception. error-text)))
             (recur))
           (do
             (if (= res false)
               (recur)
               agent)))))))

(defn thread-id
  "Returns the ID of the current thread"
  ([] (.getId (Thread/currentThread))))


(defn cmd-param-to-keyword
  "Transforms a command line argument (-something) into a keyword (:something)"
  ([atom]
     (if (keyword? atom)
       atom
       (if (.startsWith atom "-") (keyword (.substring atom 1)) atom))))

(defn check-default-values
  "Adds missing values from the default-rabbit-parameters map"
  ([opts orig] (merge-with #(if (nil? %2) %1 %2) orig opts)))

(defn extract-exception-trace
  "Extracts the information of an exception in a string"
  ([e]
     (let [sw (java.io.StringWriter.)
           pw (java.io.PrintWriter. sw)]
       (.print pw "[")
       (.print pw (.. e getClass getName))
       (.print pw "] ")
       (.print pw (. e getMessage))
       (. e (printStackTrace pw))
       (str sw))))

(defn extract-local-part-uri
  ([uri]
     (let [uri-str (str uri)]
       (if (not (= (.indexOf uri-str "#") -1))
         (aget (.split uri-str "#") 1)
         (first (reverse (vec (.split uri-str "/"))))))))
