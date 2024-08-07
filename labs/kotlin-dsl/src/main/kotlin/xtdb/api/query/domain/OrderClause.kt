package xtdb.api.query.domain

import clojure.lang.Keyword
import clojure.lang.Symbol
import xtdb.api.underware.kw

data class OrderClause(val symbol: Symbol, val direction: Direction) {
    enum class Direction(val keyword: Keyword) {
        ASC("asc".kw),
        DESC("desc".kw)
    }
}

