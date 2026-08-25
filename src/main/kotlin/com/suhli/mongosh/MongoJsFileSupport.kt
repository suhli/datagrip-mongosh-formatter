package com.suhli.mongosh

import com.intellij.psi.PsiFile

object MongoJsFileSupport {
    fun isMongoJs(file: PsiFile): Boolean {
        if (isMongoJsLanguage(file.language.id)) {
            return true
        }
        return file.viewProvider.languages.any { isMongoJsLanguage(it.id) }
    }

    fun isMongoJsLanguage(languageId: String): Boolean = languageId.startsWith("MongoJS")
}
