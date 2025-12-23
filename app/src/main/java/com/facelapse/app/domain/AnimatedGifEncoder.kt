package com.facelapse.app.domain

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Paint
import java.io.IOException
import java.io.OutputStream

class AnimatedGifEncoder {

    protected var width: Int = 0 // image size
    protected var height: Int = 0
    protected var x: Int = 0
    protected var y: Int = 0
    protected var transparentColor: Int = -1 // transparent color if given
    protected var transIndex: Int = 0 // transparent index in color table
    protected var repeatCount: Int = -1 // no repeat
    protected var frameDelay: Int = 0 // frame delay (hundredths)
    protected var started: Boolean = false // ready to output frames
    protected var out: OutputStream? = null
    protected var image: Bitmap? = null // current frame
    protected var pixels: ByteArray? = null // BGR byte array from frame
    protected var rgbIntArray: IntArray? = null // Reuse buffer for bitmap pixels
    protected var indexedPixels: ByteArray? = null // converted frame indexed to palette
    protected var colorDepth: Int = 0 // number of bit planes
    protected var colorTab: ByteArray? = null // RGB palette
    protected val usedEntry = BooleanArray(256) // active palette entries
    protected var palSize: Int = 7 // color table size (bits-1)
    protected var disposalCode: Int = -1 // disposal code (-1 = use default)
    protected var closeStream: Boolean = false // close stream when finished
    protected var firstFrame: Boolean = true
    protected var sizeSet: Boolean = false // if false, get size from first frame
    protected var sample: Int = 10 // default sample interval for quantizer

    fun setDelay(ms: Int) {
        frameDelay = ms / 10
    }

    fun setDispose(code: Int) {
        if (code >= 0) {
            disposalCode = code
        }
    }

    fun setRepeat(iter: Int) {
        if (iter >= 0) {
            repeatCount = iter
        }
    }

    fun setTransparent(c: Int) {
        transparentColor = c
    }

    fun addFrame(im: Bitmap?): Boolean {
        if (im == null || !started) {
            return false
        }
        var ok = true
        try {
            if (!sizeSet) {
                // use first frame's size
                setSize(im.width, im.height)
            }
            image = im
            getImagePixels() // convert to correct format if necessary
            analyzePixels() // build color table & map pixels
            if (firstFrame) {
                writeLSD() // logical screen descriptior
                writePalette() // global color table
            if (repeatCount >= 0) {
                    // use NS app extension to indicate reps
                    writeNetscapeExt()
                }
            }
            writeGraphicCtrlExt() // write graphic control extension
            writeImageDesc() // image descriptor
            if (!firstFrame) {
                writePalette() // local color table
            }
            writePixels() // encode and write pixel data
            firstFrame = false
        } catch (e: IOException) {
            ok = false
        }

        return ok
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out?.write(0x3b) // gif trailer
            out?.flush()
            if (closeStream) {
                out?.close()
            }
        } catch (e: IOException) {
            ok = false
        }

        // reset for subsequent use
        transIndex = 0
        out = null
        image = null
        pixels = null
        rgbIntArray = null
        indexedPixels = null
        colorTab = null
        closeStream = false
        firstFrame = true

        return ok
    }

    fun setFrameRate(fps: Float) {
        if (fps != 0f) {
            frameDelay = (100 / fps).toInt()
        }
    }

    fun setQuality(quality: Int) {
        var q = quality
        if (q < 1) q = 1
        sample = q
    }

    fun setSize(w: Int, h: Int) {
        width = w
        height = h
        if (width < 1) width = 320
        if (height < 1) height = 240
        sizeSet = true
    }

    fun setPosition(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    fun start(os: OutputStream?): Boolean {
        if (os == null) return false
        var ok = true
        closeStream = false
        out = os
        try {
            writeString("GIF89a") // header
        } catch (e: IOException) {
            ok = false
        }
        return ok.also { started = it }
    }

    protected fun analyzePixels() {
        val len = pixels!!.size
        val nPix = len / 3
        if (indexedPixels == null || indexedPixels!!.size != nPix) {
            indexedPixels = ByteArray(nPix)
        }
        val nq = NeuQuant(pixels!!, len, sample)
        // initialize quantizer
        colorTab = nq.process() // create reduced palette
        // convert map from BGR to RGB
        for (i in 0 until colorTab!!.size step 3) {
            val temp = colorTab!![i]
            colorTab!![i] = colorTab!![i + 2]
            colorTab!![i + 2] = temp
            usedEntry[i / 3] = false
        }
        // map image pixels to new palette
        var k = 0
        for (i in 0 until nPix) {
            val index = nq.map(pixels!![k++].toInt() and 0xff, pixels!![k++].toInt() and 0xff, pixels!![k++].toInt() and 0xff)
            usedEntry[index] = true
            indexedPixels!![i] = index.toByte()
        }
        colorDepth = 8
        palSize = 7
        // get closest match to transparent color if specified
        if (transparentColor != -1) {
            transIndex = findClosest(transparentColor)
        }
    }

    protected fun findClosest(c: Int): Int {
        if (colorTab == null) return -1
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = (c shr 0) and 0xff
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colorTab!!.size
        var i = 0
        while (i < len) {
            val dr = r - (colorTab!![i++].toInt() and 0xff)
            val dg = g - (colorTab!![i++].toInt() and 0xff)
            val db = b - (colorTab!![i].toInt() and 0xff)
            val d = dr * dr + dg * dg + db * db
            val index = i / 3
            if (usedEntry[index] && d < dmin) {
                dmin = d
                minpos = index
            }
            i++
        }
        return minpos
    }

    protected fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height
        if (w != width || h != height) {
            // create new image with right size/format
            val temp = Bitmap.createBitmap(width, height, Config.RGB_565)
            val g = Canvas(temp)
            g.drawBitmap(image!!, 0f, 0f, Paint())
            image = temp
        }
        val data = getImageData(image!!)
        val currentW = image!!.width
        val currentH = image!!.height
        val numPixels = currentW * currentH

        if (pixels == null || pixels!!.size != numPixels * 3) {
            pixels = ByteArray(numPixels * 3)
        }
        var tind = 0
        for (i in 0 until numPixels) {
            val td = data[i]
            pixels!![tind++] = (td and 0xFF).toByte()
            pixels!![tind++] = ((td shr 8) and 0xFF).toByte()
            pixels!![tind++] = ((td shr 16) and 0xFF).toByte()
        }
    }

    protected fun getImageData(img: Bitmap): IntArray {
        val w = img.width
        val h = img.height
        val requiredSize = w * h

        if (rgbIntArray == null || rgbIntArray!!.size != requiredSize) {
            rgbIntArray = IntArray(requiredSize)
        }

        img.getPixels(rgbIntArray!!, 0, w, 0, 0, w, h)
        return rgbIntArray!!
    }

    protected fun writeGraphicCtrlExt() {
        out!!.write(0x21) // extension introducer
        out!!.write(0xf9) // GCE label
        out!!.write(4) // data block size
        var transp: Int
        var disp: Int
        if (transparentColor == -1) {
            transp = 0
            disp = 0 // dispose = no action
        } else {
            transp = 1
            disp = 2 // force clear if using transparent color
        }
        if (disposalCode >= 0) {
            disp = disposalCode and 7 // user override
        }
        disp = disp shl 2

        // packed fields
        out!!.write(
            0 or // 1:3 reserved
                    disp or // 4:6 disposal
                    0 or // 7 user input - 0 = none
                    transp
        ) // 8 transparency flag

        writeShort(frameDelay) // delay x 1/100 sec
        out!!.write(transIndex) // transparent color index
        out!!.write(0) // block terminator
    }

    protected fun writeImageDesc() {
        out!!.write(0x2c) // image separator
        writeShort(x) // image position x,y = 0,0
        writeShort(y)
        writeShort(width) // image size
        writeShort(height)
        // packed fields
        if (firstFrame) {
            // no LCT - GCT is used for first (or only) frame
            out!!.write(0)
        } else {
            // specify normal LCT
            out!!.write(
                0x80 or // 1 local color table 1=yes
                        0 or // 2 interlace - 0=no
                        0 or // 3 sorted - 0=no
                        0 or // 4-5 reserved
                        palSize
            ) // 6-8 size of color table
        }
    }

    protected fun writeLSD() {
        // logical screen size
        writeShort(width)
        writeShort(height)
        // packed fields
        out!!.write(
            0x80 or // 1 : global color table flag = 1 (gct used)
                    0x70 or // 2-4 : color resolution = 7
                    0x00 or // 5 : gct sort flag = 0
                    palSize
        ) // 6-8 : gct size

        out!!.write(0) // background color index
        out!!.write(0) // pixel aspect ratio - assume 1:1
    }

    protected fun writeNetscapeExt() {
        out!!.write(0x21) // extension introducer
        out!!.write(0xff) // app extension label
        out!!.write(11) // block size
        writeString("NETSCAPE" + "2.0") // app id + auth code
        out!!.write(3) // sub-block size
        out!!.write(1) // loop sub-block id
        writeShort(repeatCount) // loop count (extra iterations, 0=repeat forever)
        out!!.write(0) // block terminator
    }

    protected fun writePalette() {
        out!!.write(colorTab!!, 0, colorTab!!.size)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) {
            out!!.write(0)
        }
    }

    protected fun writePixels() {
        val encoder = LZWEncoder(width, height, indexedPixels!!, colorDepth)
        encoder.encode(out!!)
    }

    protected fun writeShort(value: Int) {
        out!!.write(value and 0xff)
        out!!.write((value shr 8) and 0xff)
    }

    protected fun writeString(s: String) {
        for (i in 0 until s.length) {
            out!!.write(s[i].code)
        }
    }
}

class NeuQuant(thepic: ByteArray, len: Int, sample: Int) {

    protected val netsize = 256 /* number of colours used */

    /* four primes near 500 - assume no image has a length so large */
    /* that it is divisible by all four primes */
    protected val prime1 = 499
    protected val prime2 = 491
    protected val prime3 = 487
    protected val prime4 = 503

    protected val minpicturebytes = (3 * prime4)

    protected val maxnetpos = (netsize - 1)
    protected val netbiasshift = 4 /* bias for colour values */
    protected val ncycles = 100 /* no. of learning cycles */

    /* defs for freq and bias */
    protected val intbiasshift = 16 /* bias for fractions */
    protected val intbias = 1 shl intbiasshift
    protected val gammashift = 10 /* gamma = 1024 */
    protected val gamma = 1 shl gammashift
    protected val betashift = 10
    protected val beta = intbias shr betashift /* beta = 1/1024 */
    protected val betagamma = intbias shl (gammashift - betashift)

    /* defs for decreasing radius factor */
    protected val initrad = (netsize shr 3) /* for 256 cols, radius starts */
    protected val radiusbiasshift = 6 /* at 32.0 biased by 6 bits */
    protected val radiusbias = 1 shl radiusbiasshift
    protected val initradius = (initrad * radiusbias) /* and decreases by a */
    protected val radiusdec = 30 /* factor of 1/30 each cycle */

    /* defs for decreasing alpha factor */
    protected val alphabiasshift = 10 /* alpha starts at 1.0 */
    protected val initalpha = 1 shl alphabiasshift
    protected var alphadec: Int = 0 /* biased by 10 bits */

    /* radbias and alpharadbias used for radpower calculation */
    protected val radbiasshift = 8
    protected val radbias = 1 shl radbiasshift
    protected val alpharadbshift = (alphabiasshift + radbiasshift)
    protected val alpharadbias = 1 shl alpharadbshift

    protected var thepicture: ByteArray = thepic
    protected var lengthcount: Int = len
    protected var samplefac: Int = sample

    protected var network: Array<IntArray> = Array(netsize) { IntArray(4) }
    protected var netindex: IntArray = IntArray(256)
    protected var bias: IntArray = IntArray(netsize)
    protected var freq: IntArray = IntArray(netsize)
    protected var radpower: IntArray = IntArray(initrad)

    init {
        var i: Int
        var p: IntArray

        for (i in 0 until netsize) {
            p = network[i]
            p[0] = (i shl (netbiasshift + 8)) / netsize
            p[1] = p[0]
            p[2] = p[0]
            freq[i] = intbias / netsize /* 1/netsize */
            bias[i] = 0
        }
    }

    fun colorMap(): ByteArray {
        val map = ByteArray(3 * netsize)
        val index = IntArray(netsize)
        for (i in 0 until netsize)
            index[network[i][3]] = i
        var k = 0
        for (i in 0 until netsize) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    fun inxbuild() {
        var i: Int
        var j: Int
        var smallpos: Int
        var smallval: Int
        var p: IntArray
        var q: IntArray
        var previouscol: Int
        var startpos: Int

        previouscol = 0
        startpos = 0
        for (i in 0 until netsize) {
            p = network[i]
            smallpos = i
            smallval = p[1] /* index on g */
            /* find smallest in i..netsize-1 */
            for (j in i + 1 until netsize) {
                q = network[j]
                if (q[1] < smallval) { /* index on g */
                    smallpos = j
                    smallval = q[1] /* index on g */
                }
            }
            q = network[smallpos]
            /* swap p (i) and q (smallpos) entries */
            if (i != smallpos) {
                j = q[0]
                q[0] = p[0]
                p[0] = j
                j = q[1]
                q[1] = p[1]
                p[1] = j
                j = q[2]
                q[2] = p[2]
                p[2] = j
                j = q[3]
                q[3] = p[3]
                p[3] = j
            }
            /* smallval entry is now in position i */
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) shr 1
                for (j in previouscol + 1 until smallval)
                    netindex[j] = i
                previouscol = smallval
                startpos = i
            }
        }
        netindex[previouscol] = (startpos + maxnetpos) shr 1
        for (j in previouscol + 1 until 256)
            netindex[j] = maxnetpos /* really 256 */
    }

    fun learn() {
        var i: Int
        var j: Int
        var b: Int
        var g: Int
        var r: Int
        var radius: Int
        var rad: Int
        var alpha: Int
        var step: Int
        var delta: Int
        var samplepixels: Int
        var p: ByteArray
        var pix: Int
        var lim: Int

        if (lengthcount < minpicturebytes)
            samplefac = 1
        alphadec = 30 + ((samplefac - 1) / 3)
        p = thepicture
        pix = 0
        lim = lengthcount
        samplepixels = lengthcount / (3 * samplefac)
        delta = samplepixels / ncycles
        alpha = initalpha
        radius = initradius

        rad = radius shr radiusbiasshift
        if (rad <= 1)
            rad = 0
        for (i in 0 until rad)
            radpower[i] = alpha * (((rad * rad - i * i) * radbias) / (rad * rad))

        if (lengthcount < minpicturebytes)
            step = 3
        else if ((lengthcount % prime1) != 0)
            step = 3 * prime1
        else {
            if ((lengthcount % prime2) != 0)
                step = 3 * prime2
            else {
                if ((lengthcount % prime3) != 0)
                    step = 3 * prime3
                else
                    step = 3 * prime4
            }
        }

        i = 0
        while (i < samplepixels) {
            b = (p[pix + 0].toInt() and 0xff) shl netbiasshift
            g = (p[pix + 1].toInt() and 0xff) shl netbiasshift
            r = (p[pix + 2].toInt() and 0xff) shl netbiasshift
            j = contest(b, g, r)

            altersingle(alpha, j, b, g, r)
            if (rad != 0)
                alterneigh(rad, j, b, g, r) /* alter neighbours */

            pix += step
            if (pix >= lim)
                pix -= lengthcount

            i++
            if (delta == 0)
                delta = 1
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / radiusdec
                rad = radius shr radiusbiasshift
                if (rad <= 1)
                    rad = 0
                for (j in 0 until rad)
                    radpower[j] = alpha * (((rad * rad - j * j) * radbias) / (rad * rad))
            }
        }
    }

    fun map(b: Int, g: Int, r: Int): Int {
        var i: Int
        var j: Int
        var dist: Int
        var a: Int
        var bestd: Int
        var p: IntArray
        var best: Int

        bestd = 1000 /* biggest possible dist is 256*3 */
        best = -1
        i = netindex[g] /* index on g */
        j = i - 1 /* start at netindex[g] and work outwards */

        while ((i < netsize) || (j >= 0)) {
            if (i < netsize) {
                p = network[i]
                dist = p[1] - g /* inx key */
                if (dist >= bestd)
                    i = netsize /* stop iter */
                else {
                    i++
                    if (dist < 0)
                        dist = -dist
                    a = p[0] - b
                    if (a < 0)
                        a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0)
                            a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                p = network[j]
                dist = g - p[1] /* inx key - reverse dif */
                if (dist >= bestd)
                    j = -1 /* stop iter */
                else {
                    j--
                    if (dist < 0)
                        dist = -dist
                    a = p[0] - b
                    if (a < 0)
                        a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0)
                            a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return (best)
    }

    fun process(): ByteArray {
        learn()
        unbiasnet()
        inxbuild()
        return colorMap()
    }

    fun unbiasnet() {
        for (i in 0 until netsize) {
            network[i][0] = network[i][0] shr netbiasshift
            network[i][1] = network[i][1] shr netbiasshift
            network[i][2] = network[i][2] shr netbiasshift
            network[i][3] = i /* record colour no */
        }
    }

    protected fun alterneigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        var j: Int
        var k: Int
        var lo: Int
        var hi: Int
        var a: Int
        var m: Int
        var p: IntArray

        lo = i - rad
        if (lo < -1)
            lo = -1
        hi = i + rad
        if (hi > netsize)
            hi = netsize

        j = i + 1
        k = i - 1
        m = 1
        while ((j < hi) || (k > lo)) {
            a = radpower[m++]
            if (j < hi) {
                p = network[j++]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (e: Exception) {
                } // prevents 1.3 miscompilation
            }
            if (k > lo) {
                p = network[k--]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (e: Exception) {
                }
            }
        }
    }

    protected fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        /* alter hit neuron */
        val n = network[i]
        n[0] -= (alpha * (n[0] - b)) / initalpha
        n[1] -= (alpha * (n[1] - g)) / initalpha
        n[2] -= (alpha * (n[2] - r)) / initalpha
    }

    protected fun contest(b: Int, g: Int, r: Int): Int {
        /* finds closest neuron (min dist) and updates freq */
        /* finds best neuron (min dist-bias) and returns position */
        /* for frequently chosen neurons, freq[i] is high and bias[i] is negative */
        /* bias[i] = gamma*((1/netsize)-freq[i]) */

        var i: Int
        var dist: Int
        var a: Int
        var biasdist: Int
        var betafreq: Int
        var bestpos: Int
        var bestbiaspos: Int
        var bestd: Int
        var bestbiasd: Int
        var n: IntArray

        bestd = ((1 shl 31).inv())
        bestbiasd = bestd
        bestpos = -1
        bestbiaspos = bestpos

        for (i in 0 until netsize) {
            n = network[i]
            dist = n[0] - b
            if (dist < 0)
                dist = -dist
            a = n[1] - g
            if (a < 0)
                a = -a
            dist += a
            a = n[2] - r
            if (a < 0)
                a = -a
            dist += a
            if (dist < bestd) {
                bestd = dist
                bestpos = i
            }
            biasdist = dist - ((bias[i]) shr (intbiasshift - netbiasshift))
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist
                bestbiaspos = i
            }
            betafreq = (freq[i] shr betashift)
            freq[i] -= betafreq
            bias[i] += (betafreq shl gammashift)
        }
        freq[bestpos] += beta
        bias[bestpos] -= betagamma
        return (bestbiaspos)
    }
}

class LZWEncoder(width: Int, height: Int, pixels: ByteArray, color_depth: Int) {

    private val EOF = -1

    private var imgW: Int = width
    private var imgH: Int = height
    private var pixAry: ByteArray = pixels
    private var initCodeSize: Int = Math.max(2, color_depth)
    private var remaining: Int = 0
    private var curPixel: Int = 0

    internal val BITS = 12
    internal val HSIZE = 5003 // 80% occupancy

    internal var n_bits: Int = 0 // number of bits/code
    internal var maxbits = BITS // user settable max # bits/code
    internal var maxcode: Int = 0 // maximum code, given n_bits
    internal var maxmaxcode = 1 shl BITS // should NEVER generate this code

    internal var htab = IntArray(HSIZE)
    internal var codetab = IntArray(HSIZE)

    internal var hsize = HSIZE // for dynamic table sizing

    internal var free_ent = 0 // first unused entry

    internal var clear_flg = false

    internal var g_init_bits: Int = 0
    internal var ClearCode: Int = 0
    internal var EOFCode: Int = 0

    internal var cur_accum = 0
    internal var cur_bits = 0

    internal var masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F, 0x00FF, 0x01FF,
        0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    internal var a_count: Int = 0
    internal var accum = ByteArray(256)

    @Throws(IOException::class)
    internal fun char_out(c: Byte, outs: OutputStream) {
        accum[a_count++] = c
        if (a_count >= 254)
            flush_char(outs)
    }

    @Throws(IOException::class)
    internal fun cl_block(outs: OutputStream) {
        cl_hash(hsize)
        free_ent = ClearCode + 2
        clear_flg = true

        output(ClearCode, outs)
    }

    internal fun cl_hash(hsize: Int) {
        for (i in 0 until hsize)
            htab[i] = -1
    }

    @Throws(IOException::class)
    internal fun compress(init_bits: Int, outs: OutputStream) {
        var fcode: Int
        var i: Int /* = 0 */
        var c: Int
        var ent: Int
        var disp: Int
        var hsize_reg: Int
        var hshift: Int

        g_init_bits = init_bits

        clear_flg = false
        n_bits = g_init_bits
        maxcode = MAXCODE(n_bits)

        ClearCode = 1 shl (init_bits - 1)
        EOFCode = ClearCode + 1
        free_ent = ClearCode + 2

        a_count = 0 // clear packet

        ent = nextPixel()

        hshift = 0
        fcode = hsize
        while (fcode < 65536) {
            ++hshift
            fcode *= 2
        }
        hshift = 8 - hshift // set hash code range bound

        hsize_reg = hsize
        cl_hash(hsize_reg) // clear hash table

        output(ClearCode, outs)

        outer_loop@ while (true) {
            c = nextPixel()
            if (c == EOF) break

            fcode = (c shl maxbits) + ent
            i = (c shl hshift) xor ent // xor hashing

            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) // non-empty slot
            {
                disp = hsize_reg - i // secondary hash (after G. Knott)
                if (i == 0)
                    disp = 1
                do {
                    i -= disp
                    if (i < 0)
                        i += hsize_reg

                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        continue@outer_loop
                    }
                } while (htab[i] >= 0)
            }
            output(ent, outs)
            ent = c
            if (free_ent < maxmaxcode) {
                codetab[i] = free_ent++ // code -> hashtable
                htab[i] = fcode
            } else
                cl_block(outs)
        }
        // Put out the final code.
        output(ent, outs)
        output(EOFCode, outs)
    }

    @Throws(IOException::class)
    fun encode(os: OutputStream) {
        os.write(initCodeSize) // write "initial code size" byte

        remaining = imgW * imgH // reset navigation variables
        curPixel = 0

        compress(initCodeSize + 1, os) // compress and write the pixel data

        os.write(0) // write block terminator
    }

    @Throws(IOException::class)
    internal fun flush_char(outs: OutputStream) {
        if (a_count > 0) {
            outs.write(a_count)
            outs.write(accum, 0, a_count)
            a_count = 0
        }
    }

    internal fun MAXCODE(n_bits: Int): Int {
        return (1 shl n_bits) - 1
    }

    private fun nextPixel(): Int {
        if (remaining == 0)
            return EOF

        --remaining

        val pix = pixAry[curPixel++]

        return pix.toInt() and 0xff
    }

    @Throws(IOException::class)
    internal fun output(code: Int, outs: OutputStream) {
        cur_accum = cur_accum and masks[cur_bits]

        if (cur_bits > 0)
            cur_accum = cur_accum or (code shl cur_bits)
        else
            cur_accum = code

        cur_bits += n_bits

        while (cur_bits >= 8) {
            char_out((cur_accum and 0xff).toByte(), outs)
            cur_accum = cur_accum shr 8
            cur_bits -= 8
        }

        if (free_ent > maxcode || clear_flg) {
            if (clear_flg) {
                n_bits = g_init_bits
                maxcode = MAXCODE(n_bits)
                clear_flg = false
            } else {
                ++n_bits
                if (n_bits == maxbits)
                    maxcode = maxmaxcode
                else
                    maxcode = MAXCODE(n_bits)
            }
        }

        if (code == EOFCode) {
            while (cur_bits > 0) {
                char_out((cur_accum and 0xff).toByte(), outs)
                cur_accum = cur_accum shr 8
                cur_bits -= 8
            }

            flush_char(outs)
        }
    }
}
