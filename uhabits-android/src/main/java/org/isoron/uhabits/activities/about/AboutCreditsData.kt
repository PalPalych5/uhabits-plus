/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.about

/**
 * Curated attribution, translator, and open-source notice metadata displayed in the About flow.
 */
data class AboutTranslatorGroup(
    val language: String,
    val translators: List<String>,
)

data class AboutOpenSourceNotice(
    val name: String,
    val license: String,
    val scope: String,
)

object AboutCreditsData {
    const val FORK_MAINTAINER = "PalPalych5"
    const val UPSTREAM_PROJECT = "Loop Habit Tracker"
    const val UPSTREAM_AUTHOR = "Álinson Santos Xavier (@iSoron)"
    const val PROJECT_LICENSE_SPDX = "GPL-3.0-or-later"
    const val SOURCE_CODE_URL = "https://github.com/PalPalych5/uhabits-plus"
    const val ISSUES_URL = "https://github.com/PalPalych5/uhabits-plus/issues"
    const val UPSTREAM_REPO_URL = "https://github.com/iSoron/uhabits"
    const val UPSTREAM_CONTRIBUTORS_URL = "https://github.com/iSoron/uhabits/graphs/contributors"

    val upstreamDevelopers: List<String> = listOf(
        "Álinson S. Xavier (@iSoron)",
        "Quentin Hibon (@hiqua)",
        "Oleg Ivashchenko (@olegivo)",
        "Kristian Tashkov (@KristianTashkov)",
        "Jakub Kalinowski (@kalina559)",
        "Rechee Jozil (@recheej)",
        "Sebastian Gallese (@sgallese)",
        "Luboš Luňák (@llunak)",
        "Bindu (@vbh)",
        "Victor Yu (@vyu1)",
        "Christoph Hennemann (@chennemann)",
        "Денис (@sciamano)",
        "Joseph Tran (@JotraN)",
        "Nikhil (@regularcoder)",
        "JanetQC",
    )

    val upstreamTranslators: List<AboutTranslatorGroup> = listOf(
        AboutTranslatorGroup(
            "Bahasa Indonesia",
            listOf("Angga Rifandi", "Dika Fitrian Dwi Putra", "Heru Yen", "Intan Ayunda", "Neysa Nasywa", "azzamsa", "raden20"),
        ),
        AboutTranslatorGroup("Català", listOf("David Nos", "carllacan")),
        AboutTranslatorGroup(
            "Dansk",
            listOf("Aputsiak Niels Janussen", "Sølv Ræven", "Yussuf", "fbruna17"),
        ),
        AboutTranslatorGroup(
            "Deutsch",
            listOf(
                "Can Altas", "Laura Sophie", "Marius Teufelweich", "Matthias Meisser",
                "Michael", "Tad Wohlrapp", "cobalt59", "fabian.bouchal", "sojusnik", "tat bz",
            ),
        ),
        AboutTranslatorGroup(
            "Español",
            listOf(
                "Ander Raso Vazquez", "Brenda Correa", "Eilif Adelvice",
                "Iabin Arteaga", "Sebastian05067", "Susanamesa", "luiandresgonzalez",
            ),
        ),
        AboutTranslatorGroup("Esperanto", listOf("4001982248998", "marco.baturan")),
        AboutTranslatorGroup("Euskara", listOf("Beriain", "Osoitz")),
        AboutTranslatorGroup(
            "Français",
            listOf(
                "François Mahé", "Mathis Chenuet", "Michael Faille", "Pierre GALIEGUE",
                "Samuel Guay", "Thibaut Girka", "Tiralka", "_translator", "roptat",
            ),
        ),
        AboutTranslatorGroup("Fārsi / فارسی", listOf("Behnood HRazy", "Eman", "Mahdi Nasiri", "Saeed Esmaili")),
        AboutTranslatorGroup("Georgian", listOf("Avalysion")),
        AboutTranslatorGroup(
            "हिन्दी / Hindi",
            listOf("Aman Satnami", "Niraj Yadav", "Ravi Rami", "Vijaykumar Borkar", "vinayak sharma"),
        ),
        AboutTranslatorGroup("Hrvatski", listOf("Ivan Krušlin", "Ivan Vlahov")),
        AboutTranslatorGroup("Icelandic", listOf("strikeCunny2245")),
        AboutTranslatorGroup("Italiano", listOf("Marco Cavazza", "androide74")),
        AboutTranslatorGroup("Magyar", listOf("Balázs Keresztury", "Isti", "gapszi")),
        AboutTranslatorGroup("Malayalam", listOf("Mathew TK")),
        AboutTranslatorGroup(
            "Nederlands",
            listOf("Blinkin", "Bryanx", "Jelle den Butter", "Mark Macaré", "chrrris1987"),
        ),
        AboutTranslatorGroup("Norsk", listOf("nitovf9292")),
        AboutTranslatorGroup("Polski", listOf("Adam Jurkiewicz", "Arkadiusz Bubak", "Jan Wojtecki", "plitwin")),
        AboutTranslatorGroup(
            "Português",
            listOf("Alinson Xavier", "Bernardo Lopes", "Gustavo Lima", "Martim Parente", "Sofia Neves", "Thamara Andrade"),
        ),
        AboutTranslatorGroup(
            "Română",
            listOf("Alex V.", "Andreea Muscalagiu", "Andrei Pleș", "StoP4Me", "bearsdens"),
        ),
        AboutTranslatorGroup("Slovenčina", listOf("dukelc")),
        AboutTranslatorGroup("Slovenščina", listOf("Dušan Strgar", "dusanstrgar")),
        AboutTranslatorGroup("Suomen kieli", listOf("Antti Kallio", "Elina Salminen", "Sofia Veijonen")),
        AboutTranslatorGroup("Svenska", listOf("Alexander Jansson", "David", "Robin")),
        AboutTranslatorGroup("Telugu", listOf("easyrepro")),
        AboutTranslatorGroup(
            "Tiếng Việt",
            listOf("Anh Quân", "Huy Ngo", "Lương Vĩnh Khang", "Trần Thái", "bruhwut", "pnhpnh"),
        ),
        AboutTranslatorGroup(
            "Türkçe",
            listOf("Alparslan Şakçi", "Caner Başaran", "Evren", "Ishmaeel", "hodanli"),
        ),
        AboutTranslatorGroup("Čeština", listOf("Radek Kuklík", "Tomáš Borovec", "andaryon", "boban77")),
        AboutTranslatorGroup("Ελληνικά", listOf("Alexander Haronitakis", "Andreas Michelakis", "DionysosDV", "c.m")),
        AboutTranslatorGroup("Български", listOf("Mihail Stefanov")),
        AboutTranslatorGroup(
            "Русский",
            listOf("Andrew Firnes", "Diana Karaseva", "Dmitriy Bogdanov", "Tanya", "engineeringforgood"),
        ),
        AboutTranslatorGroup(
            "Українська",
            listOf("Andrij Mizyk", "Oglaigh Rystard", "Prosta4ok_ua", "Rystard", "Yurii Stavytskyi", "taras-ko"),
        ),
        AboutTranslatorGroup("Српски", listOf("OP Smosher", "Rancher", "Slobodan Simić", "Đorđe Vasiljević")),
        AboutTranslatorGroup("עברית", listOf("Ohad Edri", "Omry Cohen", "Yoav Argov")),
        AboutTranslatorGroup(
            "العَرَبِية",
            listOf(
                "Al Alloush", "Boula", "Israa Z", "Mahdi Nasiri",
                "Michael Malak", "Saeed Esmaili", "Sief Tarek", "mohmans", "reyhoon",
            ),
        ),
        AboutTranslatorGroup("தமிழ்", listOf("Anshoe", "Aravinth_Earth", "Magimai Prakasam", "Mohammed Imthath")),
        AboutTranslatorGroup(
            "中文",
            listOf(
                "Bowie Chen", "JY3", "Jo Chuang", "JoeLi", "KMakoto", "Lee", "Limin Lu",
                "Liveeasy", "Star7", "Ting-Hua", "XuToTo", "hypnotichemionus", "yoding", "黄克",
            ),
        ),
        AboutTranslatorGroup(
            "日本語",
            listOf("Naofumi F", "Tomairuka", "ayane.m", "mimizuk", "pi hobbes", "yukitsubaki", "長谷川知里"),
        ),
        AboutTranslatorGroup("한국어", listOf("Josh Graham", "PILHA PARK", "Seoyul", "Sumin Son")),
    )

    val openSourceNotices: List<AboutOpenSourceNotice> = listOf(
        AboutOpenSourceNotice("UHabit Next", "GPL-3.0-or-later", "Fork maintained by PalPalych5 (derivative of Loop Habit Tracker)"),
        AboutOpenSourceNotice("Loop Habit Tracker", "GPL-3.0-or-later", "Upstream foundation by Álinson Santos Xavier <git@axavier.org> & contributors"),
        AboutOpenSourceNotice("AndroidX & Jetpack Compose", "Apache License 2.0", "UI toolkit, Compose BOM, AppCompat, DocumentFile, Emoji2, Security Crypto"),
        AboutOpenSourceNotice("Material Components for Android", "Apache License 2.0", "Theme overlays and platform components"),
        AboutOpenSourceNotice("Kotlin & kotlinx.coroutines", "Apache License 2.0", "Language runtime and asynchronous structured concurrency"),
        AboutOpenSourceNotice("kotlin-inject", "Apache License 2.0", "Compile-time dependency injection"),
        AboutOpenSourceNotice("Guava, Apache Commons Codec & JSR-305", "Apache License 2.0", "Declared collections, encoding utilities, and annotations"),
        AboutOpenSourceNotice("AppIntro", "Apache License 2.0", "First-launch onboarding slides"),
        AboutOpenSourceNotice("OpenCSV", "Apache License 2.0", "Declared CSV library dependency and transitive Apache Commons utilities"),
        AboutOpenSourceNotice("Android Desugar JDK Libs", "GPL-2.0 with Classpath Exception", "Java standard library desugaring for Android API 28+"),
        AboutOpenSourceNotice("Konfetti", "ISC License", "Completion confetti effects (Copyright Dion Segijn)"),
        AboutOpenSourceNotice("Tabler Icons", "MIT License", "Settings and UI stroke icons (Copyright Paweł Kuna)"),
        AboutOpenSourceNotice("Lucide Icons", "ISC License (Feather portions MIT)", "Habit category icons (Copyright Lucide & Feather contributors)"),
        AboutOpenSourceNotice("Material Design Icons", "CC-BY 4.0", "Legacy Google Material vector assets"),
        AboutOpenSourceNotice("FontAwesome", "SIL OFL 1.1", "Bundled icon font resource (fontawesome-webfont.ttf)"),
    )
}
