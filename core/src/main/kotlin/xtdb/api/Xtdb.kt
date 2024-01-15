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

        var extraConfig: Map<*,*> = emptyMap<Any, Any>()
    }

    @JvmStatic
    @JvmOverloads
    fun startNode(config: Config = Config()): IXtdb {
        return START_NODE.invoke(config) as IXtdb
    }

    fun startNode(build: Config.() -> Unit): IXtdb {
        return startNode(Config().also(build))
    }
}
