(ns replique.compliment.utils
  "Functions and utilities for source implementations."
  (:import java.io.File java.nio.file.Files
           [java.util.jar JarFile JarEntry]))

(def ^:dynamic *extra-metadata*
  "Signals to downstream sources which additional information about completion
  candidates they should attach . Should be a set of keywords."
  nil)

(defn fuzzy-matches?
  "Tests if symbol matches the prefix when symbol is split into parts on
  separator."
  [prefix, ^String symbol, separator]
  (when (or (.startsWith symbol prefix) (= (first prefix) (first symbol)))
    (loop [pre (rest prefix), sym (rest symbol), skipping false]
      (cond (empty? pre) true
            (empty? sym) false
            skipping (if (= (first sym) separator)
                       (recur (if (= (first pre) separator)
                                (rest pre) pre)
                              (rest sym) false)
                       (recur pre (rest sym) true))
            (= (first pre) (first sym)) (recur (rest pre) (rest sym) false)
            :else (recur pre (rest sym) (not= (first sym) separator))))))

(defn fuzzy-matches-no-skip?
  "Tests if symbol matches the prefix where separator? checks whether character
  is a separator. Unlike `fuzzy-matches?` requires separator characters to be
  present in prefix."
  [prefix, ^String symbol, separator?]
  (when (or (.startsWith symbol prefix) (= (first prefix) (first symbol)))
    (loop [pre prefix, sym symbol, skipping false]
      (cond (empty? pre) true
            (empty? sym) false
            skipping (if (separator? (first sym))
                       (recur pre sym false)
                       (recur pre (rest sym) true))
            (= (first pre) (first sym)) (recur (rest pre) (rest sym) false)
            :else (recur pre (rest sym) true)))))

(defn resolve-class
  "Tries to resolve a classname from the given symbol, or returns nil
  if classname can't be resolved."
  [ns sym]
  (when-let [val (try (ns-resolve ns sym)
                      (catch ClassNotFoundException ex nil))]
    (when (class? val) val)))

(defmacro ^{:doc "Defines a memoized function."
            :forms '([name doc-string? [params*] body])}
  defmemoized [name & fdecl]
  (let [[doc & fdecl] (if (string? (first fdecl))
                        [(first fdecl) (rest fdecl)]
                        ["" fdecl])]
    `(def ~name ~doc (memoize (fn ~@fdecl)))))

;; Classpath inspection

(def android-vm?
  "Signifies if the application is running on Android."
  (.contains ^String (System/getProperty "java.vendor") "Android"))

(defmemoized ^:private classpath
  "Returns a sequence of File objects of the elements on the classpath."
  []
  (if android-vm?
    ()
    (mapcat #(.split (or (System/getProperty %) "") File/pathSeparator)
            ["sun.boot.class.path" "java.ext.dirs" "java.class.path"
             ;; This is where Boot keeps references to dependencies.
             "fake.class.path"])))

(defn- symlink?
  "Checks if the given file is a symlink."
  [^File f]
  (Files/isSymbolicLink (.toPath f)))

(defn- file-seq-nonr
  "A tree seq on java.io.Files, doesn't resolve symlinked directories to avoid
  infinite sequence resulting from recursive symlinked directories."
  [dir]
  (tree-seq
   (fn [^File f] (and (. f (isDirectory)) (not (symlink? f))))
   (fn [^File d] (seq (. d (listFiles))))
   dir))

(defn- list-files
  "Given a path (either a jar file, directory with classes or directory with
  paths) returns all files under that path."
  [^String path, scan-jars?]
  (cond (.endsWith path "/*")
        (for [^File jar (.listFiles (File. path))
              :when (.endsWith ^String (.getName jar) ".jar")
              file (list-files (.getPath jar) scan-jars?)]
          file)

        (.endsWith path ".jar")
        (if scan-jars?
          (try (for [^JarEntry entry (enumeration-seq (.entries (JarFile. path)))
                     :when (not (.isDirectory entry))]
                 (.getName entry))
               (catch Exception e))
          ())

        (= path "") ()

        :else
        (for [^File file (file-seq-nonr (File. path))
              :when (not (.isDirectory file))]
          (.replace ^String (.getPath file) path ""))))

(defmemoized all-files-on-classpath
  "Returns a list of all files on the classpath, including those located inside
  jar files."
  []
  (mapcat #(list-files % true) (classpath)))

(defmemoized classes-on-classpath
  "Returns a map of all classes that can be located on the classpath. Key
  represent the root package of the class, and value is a list of all classes
  for that package."
  []
  (->> (for [^String file (all-files-on-classpath)
             :when (and (.endsWith file ".class") (not (.contains file "__"))
                        (not (.contains file "$")))]
         (.. (if (.startsWith file File/separator)
               (.substring file 1) file)
             (replace ".class" "") (replace File/separator ".")))
       (group-by #(subs % 0 (max (.indexOf ^String % ".") 0)))))

(defmemoized project-resources
  "Returns a list of all non-code files in the current project."
  []
  (for [path (classpath)
        ^String file (list-files path false)
        :when (not (or (empty? file) (.endsWith file ".clj")
                       (.endsWith file ".jar") (.endsWith file ".class")))]
    (if (.startsWith file File/separator)
      (.substring file 1) file)))
