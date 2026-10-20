package com.affilemanager.app.core

import java.util.Locale

/** One name contract for browser icons, preview and the local/remote editor. */
object TextFileRules {
    private val extensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml",
        "log", "html", "htm", "mht", "mhtml", "smil", "smi", "lua", "kt", "kts", "java",
        "py", "js", "jsx", "ts", "tsx", "css", "scss", "sass", "less", "sh", "bash", "zsh",
        "ini", "conf", "cfg", "toml", "properties", "gradle", "sql", "c", "h", "cpp", "hpp",
        "cs", "go", "rs", "php", "rb", "swift", "dart", "vue", "svelte", "smali", "proto",
        "graphql", "gql", "env", "gitignore", "lrc", "srt", "vtt", "ass", "ssa",
    )
    private val dotfiles = setOf(
        ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile",
        ".zshrc", ".zshenv", ".zprofile", ".zlogin", ".inputrc", ".vimrc", ".editorconfig",
    )

    fun supportsName(name: String): Boolean {
        val normalized = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return normalized in dotfiles || normalized.substringAfterLast('.', "") in extensions
    }
}
