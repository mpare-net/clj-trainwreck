(ns ring.rack
  (:import  org.jruby.embed.io.WriterOutputStream
           [org.jruby.embed ScriptingContainer LocalContextScope]
           [org.jruby.runtime CallSite MethodIndex]
            org.jruby.javasupport.JavaEmbedUtils
           [org.jruby Ruby RubyHash RubyIO RubyInteger RubyObject])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [ring.middleware.nested-params :as p]
            [zweikopf.multi :as zw]))

;; Ring decided to make this pure function private...
(def nest-params #'p/nest-params)


(def ^CallSite rack-call       (MethodIndex/getFunctionalCallSite "call"))

(defn ruby-fn [^ScriptingContainer sc ruby-code-string]
  (.runScriptlet sc
    (str "Class.new(Java::clojure.lang.AFn) {
            def invoke" ruby-code-string "
            end
          }.new()")))


(def helper-scripting-container nil)

(defn new-scripting-container []
  (ScriptingContainer. LocalContextScope/CONCURRENT))

(defn ^ScriptingContainer require-rack [^ScriptingContainer sc]
  (println (.getClassLoader sc))
  (.setLoadPaths sc (concat (.getLoadPaths sc)
                            [(.toExternalForm (io/resource "rack-1.6.4/lib"))
                             (.toExternalForm (io/resource "rack-1.6.4/lib"))]))
  (.runScriptlet sc "
    require 'rack'
    require 'rack/rewindable_input'")
  sc)


(declare rewindable)
(declare responsify)

(defn ^Ruby set-helper-runtime! [^ScriptingContainer sc]
  (def helper-scripting-container sc)
  (def helper-runtime
    (.. (require-rack sc) getProvider getRuntime))
  (def rewindable
    (ruby-fn sc "(inputs) Rack::RewindableInput.new(inputs)"))
  (def responsify
    (ruby-fn sc "(output)
      begin
        retval = []
        output.each do |s|
          #print(s.encoding, \" of \", s.size, \" bytes\\n\")
          retval.push(
            if Encoding::BINARY == s.encoding
              Java::java.io.ByteArrayInputStream.new( s.to_java_bytes )
            else
              s.to_java
            end)
        end
        if retval.size == 1
          retval[0]
        else
          Java::clojure.lang.RT.seq(retval)
        end
      ensure
        output.close if output.respond_to?(:close)
      end")))

(set-helper-runtime! (or helper-scripting-container (new-scripting-container)))


(defn ->rack-default-hash [^ScriptingContainer rs]
  (.runScriptlet rs "require 'rack'")
  (doto (RubyHash. (.. rs getProvider getRuntime))
        (.put "clojure.version"   (clojure-version))
        (.put "jruby.version"     (.runScriptlet rs "JRUBY_VERSION"))
        (.put "rack.version"      (.runScriptlet rs "::Rack::VERSION"))
        (.put "rack.multithread"  true)
        (.put "rack.multiprocess" false)
        (.put "rack.run_once"     false)
        (.put "rack.hijack?"      false)
        (.put "SCRIPT_NAME"       "")))

(defn ->RubyIO [value]
  (condp instance? value ))

(defn params-map->ruby-hash [params ^ScriptingContainer rs]
  (let [hash (RubyHash. (.. rs getProvider getRuntime))]
    (doseq [[k v] params]
      (.put hash (name k)
            (cond (map?    v) (params-map->ruby-hash v rs)
                  (vector? v) (zw/rubyize (last v) rs)
                  :else       (zw/rubyize       v  rs))))
    hash))

(defn request-map->rack-hash [{:keys [request-method uri query-string body headers form-params
                                      scheme server-name server-port remote-addr] :as request}
                              ^ScriptingContainer scripting-container ^RubyHash rack-default-hash]
  {:pre [request-method server-name uri]}
  (let [runtime (.. scripting-container getProvider getRuntime)
        body-input (when body (rewindable body))
        hash
        (doto
          ^RubyHash (.rbClone rack-default-hash)
          (.put "rack.input"        (if body body-input #_else "" ))
          (.put "rack.errors"       (RubyIO. runtime (WriterOutputStream. *err*)))
          (.put "rack.url_scheme"   (if scheme (name scheme) #_else "http"))
          (.put "REQUEST_METHOD"    (case request-method
                                      :get "GET", :post "POST", :put "PUT", :delete "DELETE"
                                      (-> request-method name str/upper-case)))
          (.put "REQUEST_URI"       uri)
          (.put "PATH_INFO"         uri)
          (.put "QUERY_STRING"      (or query-string ""))
          (.put "SERVER_NAME"       server-name)
          (.put "SERVER_PORT"       (or server-port "80"))
          (.put "REMOTE_ADDR"       remote-addr))]
    (when-not (empty? form-params)
      (.put hash "rack.request.form_input" body-input)
      ;; We don't set RACK_REQUEST_FORM_VARS, is this bad?
      ;(.put hash "rack.request.form_vars" ...)
      (.put hash "rack.request.form_hash"
            (-> form-params
                (nest-params p/parse-nested-keys)
                (params-map->ruby-hash scripting-container))))
    (when-let [content-length (some->> (get headers "content-length") (re-matches #"[0-9]+"))]
      (.put hash "CONTENT_LENGTH" content-length))
    (when-let [content-type (get headers "content-type")]
      (when-not (empty? content-type)
        (.put hash "CONTENT_TYPE" content-type)))
    ;; Put HTTP_ header variables
    (doseq [[name value] headers :when (not (#{"content-type" "content-length"} name))]
      (.put hash (str "HTTP_" (->> (.split (str name) "-")  (map str/upper-case) (str/join "_"))) value))
    ;; All done!
    hash))

(defn rack-hash->response-map [[status headers body :as response]]
  {:status  status
   :headers (->> headers (remove (fn [[k v]] (.startsWith (str k) "rack."))) (into {}))
   :body    (responsify body)})

(defn call-rack-handler [^RubyHash env ^ScriptingContainer scripting-container
                         ^RubyObject rack-handler]
  (.call rack-call (.. scripting-container getProvider getRuntime getCurrentContext)
         rack-handler rack-handler env))

(defn ring->rack->ring
  "Maps a Ring request to Rack and the response back to Ring spec"
  [request ^ScriptingContainer scripting-container rack-default-hash rack-handler]
  (-> request
      (request-map->rack-hash scripting-container rack-default-hash)
      (call-rack-handler scripting-container rack-handler)
      (rack-hash->response-map)))


(defn boot-rack
  [path-to-app ^ScriptingContainer scripting-container]
  (.setLoadPaths scripting-container (concat (.getLoadPaths scripting-container) [path-to-app]))
  (.runScriptlet scripting-container
    (str "require 'bundler'
          require 'rack'
          app, options = Rack::Builder.parse_file('" path-to-app "/config.ru')
          app")))


;;;
;;; Public API
;;;

(defn wrap-rack-handler
  ([rack-handler]
    (wrap-rack-handler rack-handler (new-scripting-container)))

  ([rack-handler scripting-container]
    (let [rack-default-hash (->rack-default-hash scripting-container)]
      (fn [request]
        (ring->rack->ring request scripting-container rack-default-hash rack-handler)))))

(defn rack-app
  ([path-to-app]
    (rack-app path-to-app (new-scripting-container)))

  ([path-to-app scripting-container]
    (-> (boot-rack path-to-app scripting-container)
        (wrap-rack-handler scripting-container))))
