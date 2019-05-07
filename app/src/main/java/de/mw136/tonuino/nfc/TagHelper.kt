package de.mw136.tonuino.nfc

import android.nfc.FormatException
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import de.mw136.tonuino.byteArrayToHex
import de.mw136.tonuino.hexToBytes

private const val TAG = "TagHelper"
private const val tonuinoSector = 1
@ExperimentalUnsignedTypes
private val tonuinoCookie = hexToBytes("1337b347").toList() // TODO add to expert settings
@ExperimentalUnsignedTypes
private val factoryKey =
    hexToBytes("FFFFFFFFFFFF") // factory preset, same as MifareClassic.KEY_DEFAULT


@ExperimentalUnsignedTypes
fun tagIdAsString(tag: Tag): String {
    return byteArrayToHex(tag.id.toUByteArray()).joinToString(":")
}

@ExperimentalUnsignedTypes
class TagData(var bytes: UByteArray = ubyteArrayOf()) : Parcelable {
    private val versionIndex = 4 // TODO remove magic byte index number

    val cookie: UByteArray
        get() = bytes.sliceArray(0 until versionIndex)
    val version: UByte
        get() = getAtWithDefault(versionIndex)
    val folder: UByte
        get() = getAtWithDefault(versionIndex + 1)
    val mode: UByte
        get() = getAtWithDefault(versionIndex + 2)
    val special: UByte
        get() = getAtWithDefault(versionIndex + 3)
    val special2: UByte
        get() = getAtWithDefault(versionIndex + 4)

    fun getAtWithDefault(index: Int, default: UByte = 0u): UByte {
        return bytes.elementAtOrElse(index) { default }
    }

    constructor(parcel: Parcel) : this() {
        bytes = parcel.createByteArray()?.toUByteArray() ?: ubyteArrayOf()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByteArray(bytes.toByteArray())
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<TagData> {
        override fun createFromParcel(parcel: Parcel): TagData {
            return TagData(parcel)
        }

        override fun newArray(size: Int): Array<TagData?> {
            return arrayOfNulls(size)
        }

        fun createDefault(): TagData {
            val versionIndex = tonuinoCookie.size
            val buffer = UByteArray(versionIndex + 3) { 0u }
            tonuinoCookie.forEachIndexed { index, value -> buffer[index] = value }
            buffer[versionIndex] = 1u // version
            buffer[versionIndex + 1] = 1u // folder
            buffer[versionIndex + 2] = 1u // mode
            return TagData(buffer)
        }
    }

    override fun toString(): String {
        return "TagData<${byteArrayToHex(bytes).joinToString(" ")}>"
    }

    /**
     * Be aware that this might truncate data!
     */
    fun toFixedLengthBuffer(size: Int): ByteArray {
        val block = UByteArray(size) { 0u }
        bytes.forEachIndexed { index, value -> block[index] = value }
        return block.toByteArray()
    }
}

@ExperimentalUnsignedTypes
fun readFromTag(tag: Tag): UByteArray {
    val id = tagIdAsString(tag)
    val techList = tag.techList
    var result = ubyteArrayOf()

    try {
        Log.i(TAG, "Tag $id techList: ${techList.joinToString(", ")}")
        if (techList.contains(MifareClassic::class.java.name)) {
            MifareClassic.get(tag)?.use { mifare -> result = readFromTag(mifare) }
        } else {
            Log.e("$TAG.readFromTag", "Tag ${id} is not a MifareClassic tag and not supported")
        }
    } catch (ex: Exception) {
        // e.g. android.nfc.TagLostException
        Log.e("$TAG.readFromTag", ex.toString())
    }

    return result
}

/**
 * Different MIFARE Classic formats:
 * MIFARE Classic Mini are 320 bytes (SIZE_MINI), with 5 sectors each of 4 blocks.
 * MIFARE Classic 1k are 1024 bytes (SIZE_1K), with 16 sectors each of 4 blocks.
 * MIFARE Classic 2k are 2048 bytes (SIZE_2K), with 32 sectors each of 4 blocks.
 * MIFARE Classic 4k are 4096 bytes (SIZE_4K). The first 32 sectors contain 4 blocks and the last 8 sectors contain 16 blocks.
 *
 * Source: https://developer.android.com/reference/android/nfc/tech/MifareClassic.html
 */
@ExperimentalUnsignedTypes
fun readFromTag(mifare: MifareClassic): UByteArray {
    if (!mifare.isConnected) mifare.connect()
    var result = ubyteArrayOf()

    val key = factoryKey.asByteArray()
    if (mifare.authenticateSectorWithKeyA(tonuinoSector, key)) {
        val blockIndex = mifare.sectorToBlock(tonuinoSector)
        val block = mifare.readBlock(blockIndex).toUByteArray()

        Log.w(TAG, "Bytes in sector: ${byteArrayToHex(block).joinToString(" ")}")

        // first 4 byte should match the tonuinoCookie
        if (block.take(tonuinoCookie.size) == tonuinoCookie) {
            Log.i(TAG, "This is a Tonuino MifareClassic tag")
        }

        // todo: trim trailing zeros?
        result = block
    } else {
        Log.e(TAG, "Authentication of sector $tonuinoSector failed!")
    }

    mifare.close()
    return result
}

enum class WriteResult { SUCCESS, UNSUPPORTED_FORMAT, AUTHENTICATION_FAILURE, TAG_UNAVAILABLE, UNKNOWN_ERROR }

@ExperimentalUnsignedTypes
fun writeTonuino(tag: Tag, data: TagData): WriteResult {
    var result = WriteResult.UNSUPPORTED_FORMAT
    val techList = tag.techList
    Log.i("$TAG.write", "Supported technologies on tag ${tagIdAsString(tag)}: ${techList.joinToString(", ")}")

    try {
        if (techList.contains(MifareClassic::class.java.name)) {
            MifareClassic.get(tag)?.use { mifare -> result = writeTag(mifare, data) }
        }
    } catch (ex: TagLostException) {
        result = WriteResult.TAG_UNAVAILABLE
    } catch (ex: FormatException) {
        result = WriteResult.UNSUPPORTED_FORMAT
    } catch (ex: Exception) {
        result = WriteResult.UNKNOWN_ERROR
    }

    return result
}

@ExperimentalUnsignedTypes
fun writeTag(mifare: MifareClassic, data: TagData): WriteResult {
    var result: WriteResult
    mifare.connect()

    val key = factoryKey.asByteArray() // TODO allow configuration
    if (mifare.authenticateSectorWithKeyB(tonuinoSector, key)) {
        val blockIndex = mifare.sectorToBlock(tonuinoSector)
        // NOTE: This could truncates data, if we have more than 16 Byte (= MifareClassic.BLOCK_SIZE)
        val block = data.toFixedLengthBuffer(MifareClassic.BLOCK_SIZE)
        mifare.writeBlock(blockIndex, block)
        Log.i(
            TAG, "Wrote ${byteArrayToHex(data.bytes)} to tag ${tagIdAsString(
                mifare.tag
            )}")
        result = WriteResult.SUCCESS
    } else {
        result = WriteResult.AUTHENTICATION_FAILURE
    }

    mifare.close()

    return result
}