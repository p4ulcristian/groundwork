(ns groundwork.web.files
  "Files from resources/public: cached a year when their name carries
   their content's hash (:hashed-files), else revalidated on every load (a
   304 when unchanged); byte ranges (206), so video plays and seeks
   everywhere. And the type a file's first bytes say it is (sniff-type),
   for uploads, whatever the browser claimed."
  (:require
   [clojure.string :as str]
   [reitit.ring :as ring]
   [ring.middleware.not-modified :refer [wrap-not-modified]])
  (:import
   (java.io File FileInputStream InputStream)
   (java.nio.charset StandardCharsets)))

;; =============================================================================
;; What a file is
;; =============================================================================

(defn- ascii [^bytes b from to]
  (when (<= to (alength b))
    (String. b (int from) (int (- to from)) StandardCharsets/ISO_8859_1)))

(defn- starts-with-bytes? [^bytes b & xs]
  (and (<= (count xs) (alength b))
       (every? true? (map-indexed (fn [i x] (= (unchecked-byte x) (aget b i))) xs))))

(defn sniff-type
  "The type a file's first bytes say it is, or nil when they say nothing we
   know. Trust this, not the name or the type the browser sent."
  [^bytes head]
  (let [brand (when (= "ftyp" (ascii head 4 8)) (ascii head 8 12))
        text  (ascii head 0 (min 512 (alength head)))]
    (cond
      (starts-with-bytes? head 0x89 0x50 0x4E 0x47)           "image/png"
      (starts-with-bytes? head 0xFF 0xD8 0xFF)                "image/jpeg"
      (= "GIF8" (ascii head 0 4))                             "image/gif"
      (and (= "RIFF" (ascii head 0 4)) (= "WEBP" (ascii head 8 12))) "image/webp"
      (= "%PDF" (ascii head 0 4))                             "application/pdf"
      (starts-with-bytes? head 0x50 0x4B 0x03 0x04)           "application/zip"
      (= "glTF" (ascii head 0 4))                             "model/gltf-binary"
      (#{"heic" "heix" "hevc" "hevx" "heim" "heis"} brand)    "image/heic"
      (#{"mif1" "msf1"} brand)                                "image/heif"
      (#{"avif" "avis"} brand)                                "image/avif"
      (= "qt  " brand)                                        "video/quicktime"
      (some? brand)                                           "video/mp4"
      (some-> text (str/starts-with? "ISO-10303-21"))         "model/step"
      (some-> text (str/starts-with? "solid "))               "model/stl"
      (some-> text str/triml (str/starts-with? "<svg"))       "image/svg+xml"
      (and text (str/starts-with? (str/triml text) "<?xml") (str/includes? text "<svg")) "image/svg+xml"
      :else                                                   nil)))

(defn file-head ^bytes [^File file]
  (with-open [in (FileInputStream. file)]
    (let [buf (byte-array 512)
          n   (.read in buf)]
      (if (pos? n) (java.util.Arrays/copyOf buf n) (byte-array 0)))))

;; =============================================================================
;; Serving files
;; =============================================================================

(defn- wrap-file-caching
  "A file whose name carries its content's hash (it matches :hashed-files)
   never changes, so browsers keep it a year; every other file is
   revalidated on each load."
  [handler hashed-files]
  (let [hashed (some-> hashed-files re-pattern)]
    (fn [request]
      (when-let [response (handler request)]
        (assoc-in response [:headers "Cache-Control"]
                  (if (and hashed (re-find hashed (:uri request)))
                    "public, max-age=31536000, immutable"
                    "no-cache"))))))

(defn- bounded
  "At most n bytes of in, then the end."
  ^InputStream [^InputStream in n]
  (let [left (atom n)]
    (proxy [java.io.FilterInputStream] [in]
      (read
        ([] (if (pos? @left)
              (let [b (.read in)] (when-not (neg? b) (swap! left dec)) b)
              -1))
        ([^bytes buf] (.read ^InputStream this buf 0 (alength buf)))
        ([^bytes buf off len]
         (if (pos? @left)
           (let [n (.read in buf (int off) (int (min len @left)))]
             (when (pos? n) (swap! left - n))
             n)
           -1)))
      (available [] (int (min (.available in) @left))))))

(defn- body-length [{:keys [body headers]}]
  (cond
    (instance? File body) (.length ^File body)
    :else                 (some-> (get headers "Content-Length") str parse-long)))

(defn- open-body ^InputStream [body]
  (if (instance? File body) (FileInputStream. ^File body) body))

(defn- wrap-ranges
  "Files answer one byte range of themselves (a video player's seeking, which
   Safari needs): 206 with that part, 416 when it's outside the file."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (let [length (body-length response)
            range  (some->> (get-in request [:headers "range"]) (re-matches #"bytes=(\d*)-(\d*)"))]
        (cond
          (or (not= 200 (:status response)) (nil? length))
          response

          (or (nil? range) (= ["" ""] (rest range)))
          (assoc-in response [:headers "Accept-Ranges"] "bytes")

          :else
          (let [[_ from to] range
                [start end] (if (str/blank? from)
                              [(max 0 (- length (parse-long to))) (dec length)]
                              [(parse-long from) (min (dec length) (if (str/blank? to) (dec length) (parse-long to)))])]
            (if (or (>= start length) (> start end))
              {:status  416
               :headers {"Content-Range" (str "bytes */" length)}
               :body    ""}
              (let [in (open-body (:body response))]
                (.skipNBytes in start)
                (-> response
                    (assoc :status 206 :body (bounded in (inc (- end start))))
                    (update :headers assoc
                            "Accept-Ranges"  "bytes"
                            "Content-Range"  (str "bytes " start "-" end "/" length)
                            "Content-Length" (str (inc (- end start)))))))))))))

(defn handler
  "The Ring handler for the files under resources/public: cached by their
   name, a 304 when unchanged, byte ranges."
  [hashed-files]
  (-> (ring/create-resource-handler {:path "/" :root "public"})
      (wrap-file-caching hashed-files)
      wrap-not-modified
      wrap-ranges))
