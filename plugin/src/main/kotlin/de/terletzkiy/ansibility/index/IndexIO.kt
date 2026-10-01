package de.terletzkiy.ansibility.index

import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.IOUtil
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/**
 * Serialisation helpers shared by the Ansible index externalizers. Integers use variable-length encoding; strings use
 * [IOUtil]'s UTF format (no 64 KB limit).
 */
internal object IndexIO {
    fun writeInt(out: DataOutput, value: Int) = DataInputOutputUtil.writeINT(out, value)

    fun readInt(input: DataInput): Int = DataInputOutputUtil.readINT(input)

    fun writeString(out: DataOutput, value: String) = IOUtil.writeUTF(out, value)

    fun readString(input: DataInput): String = IOUtil.readUTF(input)

    fun writeNullable(out: DataOutput, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) writeString(out, value)
    }

    fun readNullable(input: DataInput): String? = if (input.readBoolean()) readString(input) else null

    fun writeStrings(out: DataOutput, values: List<String>) {
        writeInt(out, values.size)
        values.forEach { writeString(out, it) }
    }

    fun readStrings(input: DataInput): List<String> = List(readInt(input)) { readString(input) }

    fun <T> writeList(out: DataOutput, values: List<T>, write: (DataOutput, T) -> Unit) {
        writeInt(out, values.size)
        values.forEach { write(out, it) }
    }

    fun <T> readList(input: DataInput, read: (DataInput) -> T): List<T> {
        val size = readInt(input)
        if (size < 0) throw IOException("Negative list size $size")
        return List(size) { read(input) }
    }

    /** Checks the format byte written by [VersionedListExternalizer]; a mismatch makes the platform rebuild the index. */
    fun checkFormat(input: DataInput, expected: Int, index: String) {
        val format = input.readUnsignedByte()
        if (format != expected) throw IOException("$index: value format $format, expected $expected")
    }
}

/**
 * Writes a list of entries behind a format byte, so a value written by an older plugin version is rejected (and the
 * index rebuilt) instead of being misread. Bump [format] together with the index version whenever the layout changes.
 */
internal class VersionedListExternalizer<T>(
    private val index: String,
    private val format: Int,
    private val write: (DataOutput, T) -> Unit,
    private val read: (DataInput) -> T,
) : DataExternalizer<List<T>> {
    override fun save(out: DataOutput, value: List<T>) {
        out.writeByte(format)
        IndexIO.writeList(out, value, write)
    }

    override fun read(input: DataInput): List<T> {
        IndexIO.checkFormat(input, format, index)
        return IndexIO.readList(input, read)
    }
}
