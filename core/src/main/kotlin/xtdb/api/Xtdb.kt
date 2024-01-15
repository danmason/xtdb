package xtdb.api

import clojure.java.api.Clojure
import clojure.lang.IFn
import clojure.lang.Symbol
import xtdb.api.log.Log
import java.nio.file.Path

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

    interface TxLogConfig {
        fun open(): Log
    }
    object InMemoryTxLogConfig: TxLogConfig {
        init {
            Clojure.`var`("clojure.core", "require").invoke(Symbol.intern("xtdb.log.memory-log"))
        }
        private val OPEN_LOG: IFn = Clojure.`var`("xtdb.log.memory-log", "open-log")
        override fun open() = OPEN_LOG.invoke() as Log
    }

    class LocalTxLogConfig(val rootPath: Path): TxLogConfig {
        override fun open(): Log {
            TODO("Not yet implemented")
        }

    }

    class Config {
        @JvmField
        val indexer = IndexerConfig()

        var txLog: TxLogConfig = InMemoryTxLogConfig
    }

    @JvmStatic
    fun startNodeConfig(config: Config): IXtdb {
        return START_NODE.invoke(config) as IXtdb
    }

    fun startNodeConfig(build: Config.() -> Unit): IXtdb {
        return startNodeConfig(Config().also(build))
    }
}
