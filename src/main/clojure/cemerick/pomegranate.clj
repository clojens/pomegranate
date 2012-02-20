(ns cemerick.pomegranate
  (:import (clojure.lang DynamicClassLoader)
           (java.net URL URLClassLoader))
  (:require [clojure.java.io :as io]
            [cemerick.pomegranate.aether :as aether])
  (:refer-clojure :exclude (add-classpath)))

;; call-method pulled from clojure.contrib.reflect, (c) 2010 Stuart Halloway & Contributors
(defn- call-method
  "Calls a private or protected method.

  params is a vector of classes which correspond to the arguments to
  the method e

  obj is nil for static methods, the instance object otherwise.

  The method-name is given a symbol or a keyword (something Named)."
  [klass method-name params obj & args]
  (-> klass (.getDeclaredMethod (name method-name)
                                (into-array Class params))
    (doto (.setAccessible true))
    (.invoke obj (into-array Object args))))

(defprotocol AddURL
  "Ability to dynamically add urls to classloaders"
  (can-modify? [this] "Returns true if the given classloader can be modified.")
  (add-url [this url] "add the url to the classpath"))

(extend-type DynamicClassLoader
  AddURL
  (can-modify? [this] true)
  (add-url [this url] (.addURL this url)))

(def ^:private url-classloader-base
  {:can-modify? (constantly true)
   :add-url (fn [this url]
              (call-method URLClassLoader 'addURL [URL] this url))})

(extend URLClassLoader AddURL url-classloader-base)

(defmacro when-resolves
  [sym & body]
  (when (resolve sym) `(do ~@body)))

(when-resolves sun.misc.Launcher             
  (extend sun.misc.Launcher$ExtClassLoader AddURL
    (assoc url-classloader-base
           :can-modify? (constantly false))))

(defn- classloader-hierarchy
  []
  (->> (clojure.lang.RT/baseLoader)
    (iterate #(.getParent %))
    (take-while boolean)))

(def ^:private suitable-classloader? #(and (satisfies? AddURL %)
                                           (can-modify? %)))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
    (add-url classloader (.toURL (io/file jar-or-dir))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter suitable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. "Could not find a suitable classloader to modify from " classloaders))))))

(defn add-dependencies
  "Resolves a set of dependencies, optionally against a set of additional Maven repositories
   (Maven central is used when blank), and adds all of the resulting artifacts
   (jar files) to the current runtime via `cemerick.pomegranate/add-classpath`.  e.g.

   (add-dependencies '[[incanter \"1.2.3\"]]
                     :repositories (merge cemerick.pomegranate.aether/maven-central
                                          {\"clojars\" \"http://clojars.org/repo\"}))

   (Note that Maven central is used as the sole repository if none are specified.
    If :repositories are provided, then you must merge in the `maven-central` map from
    the cemerick.pomegranate.aether namespace yourself.)"
  [coordinates & {:keys [repositories]}]
  (doseq [artifact-file (aether/dependency-files (aether/resolve-dependencies
                                                   :coordinates coordinates
                                                   :repositories repositories))]
    (add-classpath artifact-file)))

(defn get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   (System/getProperty \"java.class.path\") as a seq of URL strings."
  []
  (->> (classloader-hierarchy)
    reverse
    (mapcat #(when (instance? URLClassLoader %) (.getURLs %)))
    (map str)))

