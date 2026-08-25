package com.suhli.mongosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MongoJsFileSupportTest {
    @Test
    fun `detects mongojs language ids`() {
        assertTrue(MongoJsFileSupport.isMongoJsLanguage("MongoJS"))
        assertTrue(MongoJsFileSupport.isMongoJsLanguage("MongoJS 7.0"))
        assertFalse(MongoJsFileSupport.isMongoJsLanguage("JavaScript"))
        assertFalse(MongoJsFileSupport.isMongoJsLanguage("SQL"))
    }
}
