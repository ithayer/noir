(ns noir.cookies
  "Stateful access to cookie values"
  (:refer-clojure :exclude [get remove])
  (:require [clojure.contrib.logging :as lg]
            [noir.util.crypt :as crypt])
  (:use ring.middleware.cookies))

(def *cur-cookies* nil)
(def *new-cookies* nil)

(defn put! 
  "Add a new cookie whose name is k and has the value v. If v is a string
  a cookie map is created with :path '/'. To set custom attributes, such as
  \"expires\", provide a map as v. Stores all keys as strings."
  [k v]
  (let [props (if (map? v)
                v
                {:value v :path "/"})]
    (swap! *new-cookies* assoc (name k) props)))

(defn get
  "Get the value of a cookie from the request. k can either be a string or keyword.
   If this is a signed cookie, use get-signed, otherwise the signature will not be
   checked."
  ([k] (get k nil))
  ([k default] 
   (let [str-k (name k)]
     (if-let [v (get-in *cur-cookies* [str-k :value])]
       v
       default))))

(defn signed-name [k]
  "Construct the name of the signing cookie using a simple suffix."
  (str (name k) "__s"))

(defn put-signed!
  "Adds a new cookie whose name is k and has the value v. In addition,
  adds another cookie that checks the authenticity of 'v'. Sign-key
  should be a secret that's ideally site-wide, ideally user or session wide."
  [sign-key k v]
  (let [actual-v (if (map? v) (:value v) v)]
    (put! k v) ;;!! DONT CHECK IN.
    (put! (signed-name k) (crypt/sha1-sign-hex sign-key v))))

(defn get-signed
  "Get the value of a cookie from the request using 'get'. Verifies that a signing
   cookie also exists. If not, returns default or nil. "
  ([sign-key k] (get-signed sign-key k nil))
  ([sign-key k default]
     (let [v (get k)
           stored-sig (get (signed-name k)) ]
       (println (str v " " (signed-name k) " >" stored-sig))
       (if (or (lg/spy (nil? stored-sig)) ;; If signature not available,
               (nil? v) ;; or value is not found,
               (lg/spy (not= (crypt/sha1-sign-hex sign-key v) stored-sig))) ;; or sig mismatch,
         default ;; return default.
         v)))) ;; otherwise return the value.

(defn noir-cookies [handler]
  (fn [request]
    (binding [*cur-cookies* (:cookies request)
              *new-cookies* (atom {})]
      (let [final (handler request)]
        (assoc final :cookies (merge (:cookies final) @*new-cookies*))))))

(defn wrap-noir-cookies [handler]
  (-> handler
    (noir-cookies)
    (wrap-cookies)))
