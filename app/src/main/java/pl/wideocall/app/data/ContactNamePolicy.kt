package pl.wideocall.app.data

object ContactNamePolicy {
    const val MAX_LENGTH = 60

    fun validate(raw: String): Result<String> = runCatching {
        val name = raw.trim()
        require(name.isNotEmpty()) { "Wpisz nazwę urządzenia." }
        require(name.length <= MAX_LENGTH) { "Nazwa może mieć najwyżej $MAX_LENGTH znaków." }
        require(name.none { it.isISOControl() }) { "Nazwa nie może zawierać znaków sterujących." }
        name
    }
}
