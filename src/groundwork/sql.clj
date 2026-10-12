(ns groundwork.sql
  "SQL text with :name params -> a JDBC statement with ? and the param order.

     SELECT post__id FROM posts WHERE post__author_id = :post/author-id LIMIT :limit
     => {:sql    \"SELECT post__id FROM posts WHERE post__author_id = ? LIMIT ?\"
         :params [:post/author-id :limit]}

   A field of the data is namespaced (:post/title); a control of the query
   is plain (:limit).

   - A name used twice is two ?s, with its value bound twice.
   - A ? that isn't a param (the jsonb operators ?, ?|, ?&) becomes ??,
     which JDBC sends as ?.
   - A colon right after a letter, digit or _ is not a param, so array
     slices like arr[1:n] stay as they are.
   - Left untouched: string literals (also E'..'), quoted identifiers,
     -- and /* */ comments (nested), dollar-quoted bodies, ::casts.
   - Positional $1 throws: params are named.")

(def ^:private param-name
  #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:/[a-z][a-z0-9]*(?:-[a-z0-9]+)*)?")
(def ^:private dollar-tag #"^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$")

(defn- ident-char? [c]
  (and c (or (Character/isLetterOrDigit (char c)) (= c \_) (= c \$))))

(defn- syntax-error [message text i]
  (throw (ex-info message {:type ::syntax :position i :near (subs text i (min (count text) (+ i 30)))})))

(defn- quoted-end
  "Index just past the quoted text opening at i. A doubled quote stays
   inside; with backslash? a backslash escapes the next character."
  [^String text i backslash?]
  (let [n (count text)
        q (.charAt text i)]
    (loop [j (inc i)]
      (cond
        (>= j n)                                 n
        (and backslash? (= \\ (.charAt text j))) (recur (+ j 2))
        (not= q (.charAt text j))                (recur (inc j))
        (and (< (inc j) n) (= q (.charAt text (inc j)))) (recur (+ j 2))
        :else                                    (inc j)))))

(defn- block-comment-end [^String text i]
  (let [n (count text)]
    (loop [j (+ i 2), depth 1]
      (cond
        (>= j n)                    n
        (.startsWith text "/*" (int j)) (recur (+ j 2) (inc depth))
        (.startsWith text "*/" (int j)) (if (= 1 depth) (+ j 2) (recur (+ j 2) (dec depth)))
        :else                       (recur (inc j) depth)))))

(defn- line-end [^String text i]
  (let [j (.indexOf text "\n" (int i))]
    (if (neg? j) (count text) j)))

(defn- escape-string? [^String text i]
  (and (pos? i)
       (#{\E \e} (.charAt text (dec i)))
       (not (and (> i 1) (ident-char? (.charAt text (- i 2)))))))

(defn parse
  "SQL text -> {:sql <with ?> :params [param keyword for each ?, in order]}."
  [^String text]
  (let [n   (count text)
        out (StringBuilder.)
        at  (fn [i] (when (< i n) (.charAt text i)))]
    (loop [i 0, params []]
      (if (>= i n)
        {:sql (str out) :params params}
        (let [c    (.charAt text i)
              nxt  (at (inc i))
              prev (when (pos? i) (.charAt text (dec i)))
              copy (fn [j] (.append out text (int i) (int j)) j)]
          (cond
            (= c \')
            (recur (copy (quoted-end text i (escape-string? text i))) params)

            (= c \")
            (recur (copy (quoted-end text i false)) params)

            (and (= c \-) (= nxt \-))
            (recur (copy (line-end text i)) params)

            (and (= c \/) (= nxt \*))
            (recur (copy (block-comment-end text i)) params)

            (and (= c \$) (not (ident-char? prev)))
            (if-let [tag (re-find dollar-tag (subs text i (min n (+ i 64))))]
              (let [close (.indexOf text ^String tag (int (+ i (count tag))))]
                (recur (copy (if (neg? close) n (+ close (count tag)))) params))
              (if (and nxt (Character/isDigit (char nxt)))
                (syntax-error "Positional params ($1) aren't allowed, use :name" text i)
                (recur (copy (inc i)) params)))

            (= c \?)
            (do (.append out "??")
                (recur (inc i) params))

            (and (= c \:) (= nxt \:))
            (recur (copy (+ i 2)) params)

            (and (= c \:) (not (ident-char? prev)))
            (if-let [pname (re-find param-name (subs text (inc i)))]
              (do (.append out "?")
                  (recur (+ i 1 (count pname)) (conj params (keyword pname))))
              (recur (copy (inc i)) params))

            :else
            (recur (copy (inc i)) params)))))))

(defn bind
  "The values for a parsed statement, one per ?, from a params map.
   A param missing from the map throws; nil binds NULL."
  [{:keys [params]} values-map source]
  (mapv (fn [k]
          (if (contains? values-map k)
            (get values-map k)
            (throw (ex-info (str "Missing param " k (when source (str " for " source)))
                            {:type ::missing-param :param k :source source}))))
        params))
