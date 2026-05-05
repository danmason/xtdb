package xtdb.indexer

import xtdb.arrow.VectorReader
import xtdb.trie.ColumnName
import xtdb.util.HLL
import xtdb.util.add
import xtdb.util.createHLL

// Nulls are excluded so that rows appended before a column entered the
// put schema (which read back as null at the column's vector position)
// don't inflate that column's cardinality.
fun computeHlls(opRdr: VectorReader, fromIdx: Int, toIdx: Int): Map<ColumnName, HLL> {
    val putRdr = opRdr.vectorForOrNull("put") ?: return emptyMap()

    return putRdr.keyNames.orEmpty().associateWith { col ->
        val rdr = putRdr.vectorFor(col)
        createHLL().also { hll ->
            for (i in fromIdx..<toIdx)
                if (opRdr.getLeg(i) == "put" && !rdr.isNull(i)) hll.add(rdr, i)
        }
    }
}
