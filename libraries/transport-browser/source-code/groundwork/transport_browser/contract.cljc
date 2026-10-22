(ns groundwork.transport-browser.contract
  "What transport's server and browser halves must agree on. The same file
   is in both halves, but for its namespace; a test at the repo's root fails
   if the two ever differ.

   - the three addresses: http, sse, ws
   - the app's pages: a map by name, {:orders/page {:address \"/orders/:order-id\" …}}")

(def default-addresses
  "Where the three pipes are, unless the settings say otherwise."
  {:http "/groundwork/http" :sse "/groundwork/sse" :ws "/groundwork/ws"})

(defn- check-page [[page-name {:keys [address]}]]
  (when-not (qualified-keyword? page-name)
    (throw (ex-info (str "A page's name must be namespaced: " page-name)
                    {:type :groundwork.transport-browser/bad-page-name :name page-name})))
  (when-not (string? address)
    (throw (ex-info (str "The page " page-name " has no :address")
                    {:type :groundwork.transport-browser/page-without-address :name page-name}))))

(defn turn-pages-into-reitit-routes
  "The pages as reitit routes: [address {:name page-name …the page}]. A page
   without a namespaced name or an address throws."
  [pages]
  (run! check-page pages)
  (vec (for [[page-name page] pages]
         [(:address page) (assoc page :name page-name)])))
