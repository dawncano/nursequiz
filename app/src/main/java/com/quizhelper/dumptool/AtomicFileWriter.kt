package com.quizhelper.dumptool

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object AtomicFileWriter {
    fun write(target: File, writer: (File) -> Unit) {
        val parent = target.parentFile ?: error("Target must have a parent directory")
        if (!parent.exists() && !parent.mkdirs()) error("Cannot create ${parent.path}")
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            writer(temp)
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
