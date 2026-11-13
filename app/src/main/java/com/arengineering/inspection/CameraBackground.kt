package com.arengineering.inspection

import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class CameraBackground {
    private val positions = floatBuffer().put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val textureCoordinates = floatBuffer()
    private var program = 0
    private var positionAttribute = 0
    private var textureAttribute = 0
    private var samplerUniform = 0
    private var coordinatesReady = false
    var textureId = 0
        private set

    fun create() {
        val textures = IntArray(1)
        glGenTextures(1, textures, 0)
        textureId = textures[0]
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        val vertex = compileShader(GL_VERTEX_SHADER, vertexShader)
        val fragment = compileShader(GL_FRAGMENT_SHADER, fragmentShader)
        program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        val status = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, status, 0)
        check(status[0] == GL_TRUE) { glGetProgramInfoLog(program) }
        positionAttribute = glGetAttribLocation(program, "aPosition")
        textureAttribute = glGetAttribLocation(program, "aTexCoord")
        samplerUniform = glGetUniformLocation(program, "uCamera")
        coordinatesReady = false
    }

    fun draw(frame: Frame) {
        if (frame.timestamp == 0L) return
        if (!coordinatesReady || frame.hasDisplayGeometryChanged()) {
            positions.position(0)
            textureCoordinates.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                positions,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoordinates,
            )
            coordinatesReady = true
        }

        positions.position(0)
        textureCoordinates.position(0)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        glUniform1i(samplerUniform, 0)
        glVertexAttribPointer(positionAttribute, 2, GL_FLOAT, false, 0, positions)
        glVertexAttribPointer(textureAttribute, 2, GL_FLOAT, false, 0, textureCoordinates)
        glEnableVertexAttribArray(positionAttribute)
        glEnableVertexAttribArray(textureAttribute)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(positionAttribute)
        glDisableVertexAttribArray(textureAttribute)
    }

    private fun floatBuffer(): FloatBuffer = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun compileShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val status = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        check(status[0] == GL_TRUE) { glGetShaderInfoLog(shader) }
        return shader
    }
}

private const val vertexShader = """
    attribute vec2 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;

    void main() {
        gl_Position = vec4(aPosition, 0.0, 1.0);
        vTexCoord = aTexCoord;
    }
"""

private const val fragmentShader = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    uniform samplerExternalOES uCamera;
    varying vec2 vTexCoord;

    void main() {
        gl_FragColor = texture2D(uCamera, vTexCoord);
    }
"""
