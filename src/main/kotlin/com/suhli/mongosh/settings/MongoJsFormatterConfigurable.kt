package com.suhli.mongosh.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

class MongoJsFormatterConfigurable : BoundConfigurable("MongoJS Formatter") {
    private val settings = MongoJsFormatterSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val state = settings.state
        return panel {
            group("Prettier options") {
                row("Print width:") {
                    intTextField(0..1000)
                        .bindIntText(state::printWidth)
                }
                row("Tab width:") {
                    intTextField(1..16)
                        .bindIntText(state::tabWidth)
                }
                row {
                    checkBox("Use tabs")
                        .bindSelected(state::useTabs)
                }
                row {
                    checkBox("Semicolons")
                        .bindSelected(state::semi)
                }
                row {
                    checkBox("Single quotes")
                        .bindSelected(state::singleQuote)
                }
                row {
                    checkBox("Bracket spacing")
                        .bindSelected(state::bracketSpacing)
                }
                row("Trailing comma:") {
                    comboBox(listOf("all", "es5", "none"))
                        .bindItem(state::trailingComma.toNullableProperty())
                }
            }
        }
    }
}
