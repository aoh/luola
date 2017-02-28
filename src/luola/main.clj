(ns luola.main
  (:require [clojure.java.io :as io]
            [compojure.api.sweet :refer :all]
            [compojure.route :as route]
            [ring.util.http-response :refer [ok status content-type] :as resp]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]
            [schema.core :as s]
            ;[clojure.string :as string]
            ;[cheshire.core :as json]
            )
   (:import java.util.UUID)
   (:gen-class))

(defonce world
   (atom [1 {} {} (promise)]))

(defn turn [] (nth @world 0))
(defn board [] (nth @world 1))
(defn next-board-state-promise [] (nth @world  3))

(defn alter-world! [op]
   (swap! world
      (fn [[turn board moves next-state-promise]]
        (conj (op turn board moves) next-state-promise))))

(defn alter-world-and-deliver! [op]
   (swap! world
      (fn [[turn board moves next-state-promise]]
        (let [[_ new-board _ :as new-state] (op turn board moves)]
          (deliver next-state-promise new-board)
          (conj new-state (promise))))))

(def turn-duration 500) ;; in ms


(defn uuid []
  (str (java.util.UUID/randomUUID)))

(defn timestamp []
   (.getTime (java.util.Date.)))

; Game objects

(def empty-cell
   {:type :ground
    :value :empty})

(defn empty-cell? [thingie]
  (= thingie empty-cell))

(def wall-cell
   {:type :thing
    :value :wall})

(defn wall-cell? [thingie]
  (= thingie wall-cell))

(def spawn-cell
  {:type :thing
   :value :spawning-pool})

(defn spawn-cell? [thingie]
  (= thingie spawn-cell))

(defn make-player [name pass]
  {:type  :player
   :items []
   :name  name
   :pass  pass})

(defn player? [thingie]
  (and (map? thingie)
       (= (:type thingie) :player)))

(defn make-monster [name]
   {:type :monster
    :name name})

(defn monster? [thingie]
  (and (map? thingie)
       (= (:type thingie) :monster)))

(defn make-item [name value]
  {:type :item
   :name name
   :value value})

(defn item? [thingie]
  (and (map? thingie)
       (= (:type thingie) :item)))

; Gather board cells

(defn board-cells [board predicate]
  (loop [x 0 y 0 out []]
    (let [val (get-board board x y false)]
      (cond
        (not val)
          (if (= x 0)
            out
            (recur 0 (+ y 1) out))
        (predicate val)
          (recur (+ x 1) y (cons {:x x :y y :cell val} out))
        :else
          (recur (+ x 1) y out)))))

(defn player-cells [board]
  (board-cells board #(player? (first %))))

(defn spawn-cells [board]
  (board-cells board #(spawn-cell? (first %))))

(defn empty-spawn-cell [board]
  (rand-nth (spawn-cells board)))

; Actions

(def PlayerAction (s/enum "move" "attack"))

(def ActionTarget (s/enum "north" "east" "south" "west"))

(defn make-action [player action-type target]
  {:type action-type
   :target target
   :name (:name player)
   :timestamp (timestamp)})

(defn set-action-proposal! [actor action]
   (alter-world!
      (fn [turn board moves]
         [turn board (assoc moves (:name actor) action)])))

(defn fold-named [op state board]
   (reduce
      (fn [state [y xs]]
         (reduce
            (fn [state [x vals]]
               (if (:name (first vals))
                  (op state x y (first vals))
                  state))
            state xs))
      nil board))

;; board name -> {:x x :y y :player player-map}
(defn find-named [board name]
   (fold-named
      (fn [state x y val]
         (if (= (:name val) name)
            {:x x :y y :player val}
            state))
      nil board))

(defn get-board [board x y def]
   (get (get board y {}) x def))

(defn put-board [board x y val]
   (assoc board y
      (assoc (get board y {}) x val)))

; move

(defn can-move? [board x y]
  (let [val (get-board board x y [])]
    (or (item? (first val))
        (spawn-cell? (first val))
        (empty-cell? (first val)))))

(defn enemy-can-move? [board x y]
   (let [val (get-board board x y [])]
     (or (empty-cell? (first val))
         (spawn-cell? (first val))
         (monster? (first val))
         (item? (first val)))))

(defn step [x y dir]
   (cond
      (= dir "north") [x (- y 1)]
      (= dir "south") [x (+ y 1)]
      (= dir "west") [(- x 1) y]
      (= dir "east") [(+ x 1) y]
      :else [x y]))

(defn maybe-move [board {:keys [x y]} dir]
  (let [old (get-board board x y [])
        [xp yp] (step x y dir)]
    (if (can-move? board xp yp)
      (let [new (get-board board xp yp [])
            old-entity (first old)
            player-moving-on-item? (and (player? old-entity)
                                        (item? (first new)))
            new-entity (if player-moving-on-item?
                         (update old-entity :items conj (first new))
                         old-entity)]
        (-> board
            (put-board x y (rest old))
            (put-board xp yp (cons new-entity
                                   (if player-moving-on-item?
                                     (rest new)
                                     new)))))
      board)))

; attack

(defn can-attack? [board x y]
  (let [val (get-board board x y [])]
    (player? (first val))))

(defn maybe-attack [board {:keys [x y]} dir]
  (let [[xp yp] (step x y dir)]
    (if (can-attack? board xp yp)
      (let [new (get-board board xp yp [])]
        (put-board board xp yp (rest new)))
      board)))

(defn step-world [board actions]
   (let [actions (sort (fn [a b] (< (:timestamp a) (:timestamp b))) (vals actions))]
      (reduce
         (fn [board action]
            (println "ACTION " action)
            (let [info (find-named board (:name action))]
               (cond
                  (nil? info)
                    board
                  (= (:type action) "move")
                    (maybe-move board info (:target action))
                  (= (:type action) "attack")
                    (maybe-attack board info (:target action))
                  :else
                     (do
                        (println "Unknown action: " action)
                        board))))
         board actions)))

;;; World time

(defn unvisited-neighbours [map board poss]
   (set
      (reduce
         (fn [out [x y]]
            (reduce
               (fn [out [x y]]
                  (cond
                     (get-board map x y false)
                        out
                     (enemy-can-move? board x y)
                        (cons [x y] out)
                     :else
                        out))
               out
               [[(+ x 1) y] [(- x 1) y] [x (+ y 1)] [x (- y 1)]]))
         [] poss)))

(defn player-distance-map
  "a scalar field showing the distance to the nearest player"
  []
   (let [board (board)
         roots
            (fold-named
               (fn [roots x y val] (if (player? val) (cons [x y] roots) roots))
               [] board)]
      (loop [poss roots distance 0 map {}]
         ;(println "unvisited-neighbours " poss)
         (if (empty? poss)
            map
            (let [map (reduce (fn [map [x y]] (put-board map x y distance)) map poss)]
               (recur
                  (unvisited-neighbours map board poss)
                  (+ distance 1)
                  map))))))

(defn best-direction [mmap x y]
   (let [opts
         (map
          (fn [[x y dir]] [(get-board mmap x y 1000) x y dir])
            [[(+ x 1) y "east"]
             [(- x 1) y "west"]
             [x (+ y 1) "south"]
             [x (- y 1) "north"]])]
     (first (sort (fn [a b] (< (first a) (first b))) opts))))

(defn monster-action [monster board player-distance-map x y]
  (let [[distance x y direction] (best-direction player-distance-map x y)
        cell (get-board board x y [])]
    (cond (player? (first cell)) (make-action monster "attack" direction)
          :else                  (make-action monster "move" direction))))

(defn monsters-think []
  (let [map (player-distance-map)
        the-board (board)]
      (fold-named
         (fn [state x y val]
            (if (monster? val)
               (if (= (bit-and (turn) 1) 0)
                  ;; move half of the time
                  (set-action-proposal! val
                                        (monster-action val the-board map x y))
                  state)
               state))
         nil the-board)))

(defn time-ticker []
   (loop []
      ;(println "Turn " (turn) "ends.")
      (alter-world-and-deliver!
         (fn [turn board moves]
            [(+ turn 1)
             (step-world board moves)
             {}]))
      ;; todo, substract this from sleep
      (monsters-think)
      (Thread/sleep turn-duration)
      (recur)))

(defonce game-time
   (let [thread (Thread. time-ticker)]
      (.start thread)
      thread))


;;; Game state

(defn parse-board [string]
   (loop [board {} x 0 y 0 data (seq string)]
      (cond
         (empty? data)
            board
         (= (first data) \newline)
            (recur board 0 (+ y 1) (rest data))
         (= (first data) \.)
            (recur
               (put-board board x y [empty-cell])
               (+ x 1) y (rest data))
         (= (first data) \space)
            (recur board x y (rest data))
         (= (first data) \$)
            (recur (put-board board x y [(make-item (uuid) 10) empty-cell]) (+ x 1) y (rest data))
         (= (first data) \e)
            (recur (put-board board x y [(make-monster (uuid)) empty-cell]) (+ x 1) y (rest data))
         (= (first data) \#)
            (recur (put-board board x y [wall-cell]) (+ x 1) y (rest data))
         (= (first data) \:)
            (recur (put-board board x y [spawn-cell]) (+ x 1) y (rest data))
         :else
            (do
               (println "BAD CHAR:" (first data))
               (recur board x y (rest data))))))

;; find an empty position
(defn empty-pos [board]
   (reduce
      (fn [taken y]
         (reduce
            (fn [taken x]
               (let [val (get-board board x y false)]
                  (if (empty-cell? (first val))
                     [x y]
                     taken)))
            taken (keys (get board y))))
      false
      (keys board)))



;; board name pass → nil | player-map
(defn find-authorized-player [board name pass]
   (let [info (find-named board name)]
      (when (= (:pass (:player info)) pass)
         (:player info))))

(defn maybe-add-thing!
  ([name thing]
   (maybe-add-thing! name thing empty-pos))
  ([name thing pos-fn]
   (alter-world!
    (fn [turn board actions]
      (if (find-named board name)
        [turn board actions]
        (let [[x y :as pos] (pos-fn board)]
          (if pos
            [turn
             (put-board board x y
                        (cons thing
                              (get-board board x y [])))
             actions]
            [turn board actions])))))))

(defn maybe-add-player! [name pass]
  (maybe-add-thing! name
                    (make-player name pass)
                    #(if-let [pos (empty-spawn-cell %)]
                       [(:x pos) (:y pos)])))

(defn maybe-add-monster! []
   (let [name (uuid)]
      (maybe-add-thing! name
         (make-monster name)))
   true)

(def initial-level
   "######################################################################################
    #eeee#$..#...#...#...................................................................#
    #eeee.$#...#...#............................#........................................#
    #eeee#$..#...#..............................#........................................#
    #eeee.$#...#................................#........................................#
    ##.#.#$..#.................:................#........................................#
    #$$$$$$#....................................#........................................#
    #.#.#.#..#..................................#..................$$....................#
    #.......#...................................#........................................#
    ##.#.#.#....................................#........................................#
    #...........................................#........................................#
    #.#.#.......................................#........................................#
    #...........................................#.................................:......#
    ##.#...........:...........$$...............#........................................#
    #..........................$$...............#......................................#.#
    #.#.........................................#......................................#.#
    #...........................................#......................................#.#
    #...........................................#..........:...........................#.#
    #.......................................#########..................................#.#
    #.......................................#eeeeeee#..................................#.#
    #.......................................#ee...ee#..................................#.#
    #.##########################################.#######################################.#
    #..................................................................................#.#
    #..................................................................................#.#
    #..................................................................................#.#
    #..................................................................................#.#
    #..................................................................................#.#
    #..................................................................................#.#
    #..................................................................................#.#
    #....................................$...............................................#
    #...............:.........................................$..........................#
    #....................................................................................#
    #....................................................................................#
    #...............................................$....................................#
    #..........................$..........................................:..............#
    #....................................................................................#
    #....................................................................................#
    #...................................................$................................#
    #....................................................................................#
    #....................................................................................#
    ######################################################################################")

(def maze-level (slurp (io/resource "maze.level")))

(defn reset-game! []
   (alter-world!
      (fn [_ _ _]
         [1 (parse-board initial-level) {}])))


(defn unparse-board [board player]
   (loop [x 0 y 0 out []]
      (let [val (get-board board x y false)]
         (cond
            (not val)
               (if (= x 0)
                  (apply str (reverse out))
                  (recur 0 (+ y 1) (cons \newline out)))
            (wall-cell? (first val))
               (recur (+ x 1) y (cons \# out))
            (empty-cell? (first val))
               (recur (+ x 1) y (cons \. out))
            (spawn-cell? (first val))
               (recur (+ x 1) y (cons \: out))
            (player? (first val))
               (if (= player (:name (first val)))
                  (recur (+ x 1) y (cons \@ out))
                  (recur (+ x 1) y (cons \P out)))
            (monster? (first val))
               (recur (+ x 1) y (cons \e out))
            (item? (first val))
               (recur (+ x 1) y (cons \$ out))
            :else
               (recur (+ x 1) y (cons \? out))))))

;;; Leaderboard

(defn players [board]
  (map (comp first :cell) (player-cells board)))

(defn player->leaderboard-entry [player]
  {:name  (:name player)
   :value (->> player
               :items
               (map :value)
               (reduce +))})

(defn leaderboard [board]
  (sort-by :value > (map player->leaderboard-entry (players board))))

;;; Handler

(defn status-no-player []
  (status (ok "no") 404))

(def api-handler
   (api
      {:swagger
         {:ui "/api-docs"
          :spec "/swagger.json"
          :data {:info {:title "Luolan API"
                        :description "keinot taisteluun"}}}}

      (undocumented
         (route/resources "/")
         (GET "/" []
            (resp/temporary-redirect "/index.html")))

      (context "/api" []

         (GET "/alive" []
            :summary "check whether server is running"
            (ok "I feel happy!"))

         (GET "/reset" []
            :summary "reset game"
            (do (reset-game!)
                (ok (unparse-board (board) nil))))

         (GET "/board" []
            :query-params [name :- s/Str]
            (ok (unparse-board (board) name)))

         (GET "/next-board" []
            :query-params [name :- s/Str]
            (ok (unparse-board @(next-board-state-promise) name)))

         (GET "/add-player" []
            :query-params [name :- s/Str, pass :- s/Str]
            (do
               (maybe-add-player! name pass)
               (if-let [player (find-authorized-player (board) name pass)]
                 (ok (unparse-board (board) (:name player)))
                 (status (ok "unacceptable") 403))))

         (GET "/player" []
           :query-params [name :- s/Str, pass :- s/Str]
           (let [player (find-authorized-player (board) name pass)]
             (if player
               (ok player)
               (status-no-player))))

         (GET "/act" []
           :query-params [name :- s/Str, pass :- s/Str, action :- PlayerAction, {target :- ActionTarget nil}]
           (let [player (find-authorized-player (board) name pass)]
             (if player
               (do (set-action-proposal! player (make-action player action target))
                   (ok))
               (status-no-player))))
         (GET "/leaderboard" []
           (ok (leaderboard (board)))))))


;;; Startup and shutdown

(defonce server (atom nil))

(defn stop-server []
   (when-let [s @server]
      (println "stopping server")
      (.stop s)
      (reset! server nil)))

(defn start-luola [handler conf]
   (when server
      (stop-server))
   (stop-server)
   (reset! server (jetty/run-jetty api-handler conf))
   (when server
     (println "Luola is running")))

(s/defn start-server [conf]
   (start-luola api-handler
      {:port 8080
       :join? false}))


;;; Dev mode entry

(defn reset []
   (if server
      (do
         (println "Stopping luola")
         (stop-server)))
   (require 'luola.main :reload)
   (reset-game!)
   (start-server
      {:port 8080}))

(defn go []
   (println "reset from go")
   (reset))

(defn -main [& args]
   (start-server {}))
