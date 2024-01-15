package xtdb.api

import clojure.java.api.Clojure
import clojure.lang.IFn
import clojure.lang.Symbol

object Xtdb {
    init {
        Clojure.`var`("clojure.core", "require").invoke(Symbol.intern("xtdb.node.impl"))
    }

    private val START_NODE: IFn = Clojure.`var`("xtdb.node.impl", "start-node")

    @JvmStatic
    @JvmOverloads
    fun startNode(opts: Map<*, *> = emptyMap<Any, Any>()): IXtdb {
        return START_NODE.invoke(opts) as IXtdb
    }

    class IndexerConfig {
        var rowsPerChunk = 102400L
    }

    class Config {
        @JvmField
        val indexer = IndexerConfig()
    }

    @JvmStatic
    fun startNodeConfig(config: Config): IXtdb {
        return START_NODE.invoke(config) as IXtdb
    }

    fun startNodeConfig(build: Config.() -> Unit): IXtdb {
        return startNodeConfig(Config().also(build))
    }
}
