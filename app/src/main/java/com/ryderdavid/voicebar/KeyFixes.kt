package com.ryderdavid.voicebar

/**
 * The fixes Voicebar's keyboard makes when a word is finished with a space: "i" to "I", and the
 * contractions people type without their apostrophe ("dont", "im"). Only words that aren't words
 * on their own ("ill", "wont", "lets", "were" are, so they're left alone). ⌫ right after undoes it.
 */
object KeyFixes {
    private val FIXES = mapOf(
        "i" to "I", "im" to "I'm", "ive" to "I've", "id" to "I'd",
        "dont" to "don't", "cant" to "can't", "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't",
        "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "havent" to "haven't", "hasnt" to "hasn't",
        "hadnt" to "hadn't", "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "thats" to "that's", "whats" to "what's", "theres" to "there's", "heres" to "here's", "whos" to "who's",
        "youre" to "you're", "theyre" to "they're", "youve" to "you've", "theyve" to "they've", "weve" to "we've",
        "youll" to "you'll", "theyll" to "they'll", "itll" to "it'll", "thatll" to "that'll", "youd" to "you'd",
        "theyd" to "they'd", "shes" to "she's", "hes" to "he's", "didn" to "didn't", "wouldve" to "would've",
        "couldve" to "could've", "shouldve" to "should've", "aint" to "ain't",
    )

    /** [word] fixed, in the case it was typed in; null when it's fine as it is. */
    fun fix(word: String): String? {
        if (word.isEmpty()) return null
        val f = FIXES[word.lowercase()] ?: return null
        val out = when {
            word.length > 1 && word.all { it.isUpperCase() } -> f.uppercase()
            word[0].isUpperCase() -> f.replaceFirstChar { it.uppercaseChar() }
            else -> f
        }
        return out.takeIf { it != word }
    }
}
