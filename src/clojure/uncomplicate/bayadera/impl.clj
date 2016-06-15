(ns ^{:author "Dragan Djuric"}
    uncomplicate.bayadera.impl
  (:require [clojure.java.io :as io]
            [uncomplicate.commons.core :refer [Releaseable release wrap-float]]
            [uncomplicate.fluokitten.core :refer [op fmap!]]
            [uncomplicate.neanderthal
             [protocols :as np]
             [math :refer [sqrt]]
             [core :refer [ecount transfer]]
             [native :refer [sge]]]
            [uncomplicate.bayadera
             [protocols :refer :all]
             [math :refer [log-beta]]
             [distributions :refer :all]])
  (:import [clojure.lang IFn]))

(def ^:private INVALID_PARAMS_MESSAGE
  "Invalid params dimension. Must be %s, but is %s.")

(def ^:private USE_SAMPLE_MSG
  "This distribution's %s is a random variable. Please draw a sample to estimate it.")

(defrecord DataSetImpl [dataset-eng data-matrix]
  Releaseable
  (release [_]
    (release data-matrix))
  DataSet
  (data [_]
    data-matrix)
  Location
  (mean [this]
    (data-mean dataset-eng data-matrix))
  Spread
  (variance [this]
    (data-variance dataset-eng data-matrix))
  (sd [this]
    (fmap! sqrt (variance this)))
  EstimateEngine
  (histogram [this]
    (histogram this data-matrix)))

(deftype DirectSampler [samp-engine params sample-count ^ints fseed]
  Releaseable
  (release [_]
    true)
  RandomSampler
  (init! [_ seed]
    (aset fseed 0 (int seed)))
  (sample [_ n]
    (sample samp-engine (aget fseed 0) params n))
  (sample [this]
    (sample this sample-count))
  (sample! [this n]
    (init! this (rand-int Integer/MAX_VALUE))
    (sample this n))
  (sample! [this]
    (sample! this sample-count)))

;; ==================== Distributions ====================

(deftype GaussianDistribution [bayadera-factory dist-eng params
                               ^double mu ^double sigma]
  Releaseable
  (release [_]
    (release params))
  SamplerProvider
  (sampler [_]
    (->DirectSampler (gaussian-sampler bayadera-factory) params
                     (processing-elements bayadera-factory)
                     (int-array 1)))
  Distribution
  (parameters [_]
    params)
  EngineProvider
  (engine [_]
    dist-eng)
  ModelProvider
  (model [_]
    (model dist-eng))
  Location
  (mean [_]
    mu)
  Spread
  (variance [_]
    (gaussian-variance sigma))
  (sd [_]
    sigma))

(deftype UniformDistribution [bayadera-factory dist-eng params ^double a ^double b]
  Releaseable
  (release [_]
    (release params))
  SamplerProvider
  (sampler [_]
    (->DirectSampler (uniform-sampler bayadera-factory) params
                     (processing-elements bayadera-factory)
                     (int-array 1)))
  Distribution
  (parameters [_]
    params)
  EngineProvider
  (engine [_]
    dist-eng)
  ModelProvider
  (model [_]
    (model dist-eng))
  Location
  (mean [_]
    (uniform-mean a b))
  Spread
  (variance [_]
    (uniform-variance a b))
  (sd [_]
    (sqrt (uniform-variance a b))))

(defn ^:private prepare-mcmc-sampler
  [sampler-factory walkers params limits options]
  (let [{warm-up :warm-up
         iterations :iterations
         a :a
         :or {warm-up 512
              iterations 64
              a 2.0}} options
        samp (mcmc-sampler sampler-factory walkers params limits)]
    (set-position! samp (rand-int Integer/MAX_VALUE))
    (init! samp (rand-int Integer/MAX_VALUE))
    (burn-in! samp warm-up (wrap-float a))
    (init! samp (rand-int Integer/MAX_VALUE))
    (run-sampler! samp iterations (wrap-float a))
    samp))

(deftype BetaDistribution [bayadera-factory dist-eng params ^double a ^double b]
  Releaseable
  (release [_]
    (release params))
  SamplerProvider
  (sampler [this options]
    (let [walkers (or (:walkers options)
                      (* (long (processing-elements bayadera-factory)) 32))
          beta-model (model this)]
      (prepare-mcmc-sampler (beta-sampler bayadera-factory) walkers params
                            (limits beta-model) options)))
  (sampler [this]
    (sampler this nil))
  Distribution
  (parameters [_]
    params)
  EngineProvider
  (engine [_]
    dist-eng)
  ModelProvider
  (model [_]
    (model dist-eng))
  Location
  (mean [_]
    (beta-mean a b))
  Spread
  (variance [_]
    (beta-variance a b))
  (sd [_]
    (sqrt (beta-variance a b))))

(deftype DistributionImpl [bayadera-factory dist-eng sampler-factory params dist-model]
  Releaseable
  (release [_]
    (release params))
  SamplerProvider
  (sampler [_ options]
    (let [walkers (or (:walkers options)
                      (* (long (processing-elements bayadera-factory)) 32))]
      (prepare-mcmc-sampler sampler-factory walkers params
                            (limits dist-model) options)))
  (sampler [this]
    (sampler this nil))
  Distribution
  (parameters [_]
    params)
  EngineProvider
  (engine [_]
    dist-eng)
  ModelProvider
  (model [_]
    dist-model)
  Location
  (mean [_]
    (throw (UnsupportedOperationException. (format USE_SAMPLE_MSG "mean"))))
  Spread
  (variance [_]
    (throw (UnsupportedOperationException. (format USE_SAMPLE_MSG "variance"))))
  (sd [_]
    (throw (UnsupportedOperationException. (format USE_SAMPLE_MSG "standard deviation")))))

(deftype DistributionCreator [bayadera-factory dist-eng sampler-factory dist-model]
  Releaseable
  (release [_]
    (and
     (release dist-eng)
     (release sampler-factory)))
  IFn
  (invoke [_ params]
    (if (= (params-size dist-model) (ecount params))
      (->DistributionImpl bayadera-factory dist-eng sampler-factory
                          (transfer (np/factory bayadera-factory) params)
                          dist-model)
      (throw (IllegalArgumentException.
              (format INVALID_PARAMS_MESSAGE (params-size dist-model)
                      (ecount params))))))
  (invoke [this data hyperparams]
    (.invoke this (op data hyperparams)))
  ModelProvider
  (model [_]
    dist-model))

(deftype PosteriorCreator [^IFn dist-creator hyperparams]
  Releaseable
  (release [_]
    (and (release hyperparams)
         (release dist-creator)))
  IFn
  (invoke [_ data]
    (.invoke dist-creator data hyperparams)))
