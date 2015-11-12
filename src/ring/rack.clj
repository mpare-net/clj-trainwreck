(ns ring.rack
  (:import  org.jruby.embed.io.WriterOutputStream
           [org.jruby.embed ScriptingContainer LocalContextScope]
            org.jruby.javasupport.JavaEmbedUtils
            org.jruby.RubyIO
            org.jruby.rack.servlet.RewindableInputStream)
  (:require [clojure.string :as str]))

(defn ^java.util.Map ->rack-default-hash [^org.jruby.embed.ScriptingContainer rs]
  (.runScriptlet rs "require 'rack'")
  (doto (org.jruby.RubyHash. (.. rs getProvider getRuntime))
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

(defn request-map->rack-hash [{:keys [request-method uri query-string body headers
                                      scheme server-name server-port remote-addr] :as request}
                              runtime rack-default-hash]
  {:pre [request-method server-name uri]}
  (let [hash
        (doto
          (.rbClone rack-default-hash)
          (.put "rack.input"        (if body (RewindableInputStream. ^InputStream body)
                                     #_else "" ))
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
    (when-let [content-length (some->> (get headers "content-length") re-matches #"[0-9]+")]
      (.put hash "CONTENT_LENGTH" content-length))
    (when-let [content-type (get headers "content-type")]
      (when-not (empty? content-type)
        (.put hash "CONTENT_TYPE" content-type)))
    ;; Put HTTP_ header variables
    (doseq [[name value] headers :when (not (#{"content-type" "content-length"} name))]
      (.put hash (->> (.split (str name) "-")  (map str/upper-case) (str/join "_")) value))
    ;; All done!
    hash))

(def buffer-response
  (let [sc (ScriptingContainer.)]
    (.runScriptlet sc
      "def buf(output)
        begin
          return output[0].to_java if output.respond_to?(:size) && output.size > 1
          Java::clojure.lang.RT.seq( output.each{|s| s.to_java} )
        ensure
          output.close if output.respond_to?(:close)
         end
      end")
    sc))

(defn rack-hash->response-map [[status headers body :as response]]
  {:status status
   :headers (->> headers (remove (fn [[k v]] (.startsWith (str k) "rack."))) (into {}))
   :body    (.callMethod buffer-response nil "buf" body Object)})

(defn call-rack-handler [env scripting-container rack-handler]
  (. scripting-container callMethod rack-handler "call" env Object))

(defn ring->rack->ring
  "Maps a Ring request to Rack and the response back to Ring spec"
  [request scripting-container rack-default-hash rack-handler]
  (-> request
      (request-map->rack-hash (.. scripting-container getProvider getRuntime) rack-default-hash)
      (call-rack-handler scripting-container rack-handler)
      (rack-hash->response-map)))


(defn boot-rails
  [path-to-app scripting-container]
  (.setLoadPaths scripting-container (concat (.getLoadPaths scripting-container) [path-to-app]))
  (.runScriptlet scripting-container
    (str "require 'bundler'
          require 'rack'
          app, options = Rack::Builder.parse_file('" path-to-app "/config.ru')
          Rails.application")))


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

(defn rails-app
  ([path-to-app]
    (rails-app path-to-app (new-scripting-container)))

  ([path-to-app scripting-container]
    (-> (boot-rails path-to-app scripting-container)
        (wrap-rack-handler scripting-container))))
