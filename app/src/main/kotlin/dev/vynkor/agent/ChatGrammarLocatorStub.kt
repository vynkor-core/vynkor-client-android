package dev.vynkor.agent

import io.noties.prism4j.annotations.PrismBundle

/**
 * kapt generates [dev.vynkor.agent.ChatGrammarLocator] (a Prism4j
 * `GrammarLocatorDef`) from this stub; the annotated class itself is never
 * used at runtime.
 */
@PrismBundle(
    include = [
        "c", "clike", "cpp", "csharp", "css", "go", "java",
        "javascript", "json", "kotlin", "markdown", "markup", "python",
        "sql", "yaml",
    ],
    grammarLocatorClassName = ".ChatGrammarLocator",
)
class ChatGrammarLocatorStub
