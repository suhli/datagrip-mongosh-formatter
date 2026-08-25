package com.suhli.mongosh.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.suhli.mongosh.formatter.FormatterOptions

@State(
    name = "MongoJsFormatterSettings",
    storages = [Storage("mongoJsFormatter.xml")],
)
class MongoJsFormatterSettings : PersistentStateComponent<MongoJsFormatterSettings.State> {
    data class State(
        var printWidth: Int = 100,
        var tabWidth: Int = 2,
        var useTabs: Boolean = false,
        var semi: Boolean = true,
        var singleQuote: Boolean = false,
        var trailingComma: String = "all",
        var bracketSpacing: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun toFormatterOptions(): FormatterOptions = FormatterOptions(
        printWidth = state.printWidth,
        tabWidth = state.tabWidth,
        useTabs = state.useTabs,
        semi = state.semi,
        singleQuote = state.singleQuote,
        trailingComma = state.trailingComma,
        bracketSpacing = state.bracketSpacing,
    )

    companion object {
        fun getInstance(): MongoJsFormatterSettings =
            ApplicationManager.getApplication().getService(MongoJsFormatterSettings::class.java)
    }
}
