/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.common.icons

import org.isoron.uhabits.R

object IconCatalogData {
    val ALL_ICONS: List<IconEntry> by lazy {
        getPart1() + getPart2() + getPart3() + getPart4() + getPart5() + getPart6() + getPart7() + getPart8() + getPart9() + getPart10() + getPart11() + getPart12() + getPart13()
    }

    private fun getPart1(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:book",
            legacyKey = "book",
            drawableRes = R.drawable.ic_cat_book,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Книга",
            labelEn = "Book",
            tags = listOf("читать", "reading", "учеба", "литература")
        ),
        IconEntry(
            key = "lucide:book-open",
            legacyKey = "book-open",
            drawableRes = R.drawable.ic_cat_book_open,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Открытая книга",
            labelEn = "Open Book",
            tags = listOf("учеба", "конспект", "чтение")
        ),
        IconEntry(
            key = "lucide:bookmark",
            legacyKey = "bookmark",
            drawableRes = R.drawable.ic_cat_bookmark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Закладка",
            labelEn = "Bookmark",
            tags = listOf("заметка", "важное")
        ),
        IconEntry(
            key = "lucide:graduation-cap",
            legacyKey = "graduation-cap",
            drawableRes = R.drawable.ic_cat_graduation_cap,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Выпускной / Академия",
            labelEn = "Graduation Cap",
            tags = listOf("университет", "вуз", "диплом", "курсы")
        ),
        IconEntry(
            key = "lucide:school",
            legacyKey = "school",
            drawableRes = R.drawable.ic_cat_school,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Школа / Вуз",
            labelEn = "School",
            tags = listOf("учеба", "здание", "образование")
        ),
        IconEntry(
            key = "lucide:pencil",
            legacyKey = "pencil",
            drawableRes = R.drawable.ic_cat_pencil,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Карандаш",
            labelEn = "Pencil",
            tags = listOf("писать", "запись", "черновик")
        ),
        IconEntry(
            key = "lucide:pen-tool",
            legacyKey = "pen-tool",
            drawableRes = R.drawable.ic_cat_pen_tool,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Перо",
            labelEn = "Pen Tool",
            tags = listOf("дизайн", "письмо", "каллиграфия")
        ),
        IconEntry(
            key = "lucide:calculator",
            legacyKey = "calculator",
            drawableRes = R.drawable.ic_cat_calculator,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Калькулятор",
            labelEn = "Calculator",
            tags = listOf("математика", "расчеты", "счет")
        ),
        IconEntry(
            key = "lucide:library",
            legacyKey = "library",
            drawableRes = R.drawable.ic_cat_library,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Библиотека",
            labelEn = "Library",
            tags = listOf("книги", "архив", "знания")
        ),
        IconEntry(
            key = "lucide:microscope",
            legacyKey = "microscope",
            drawableRes = R.drawable.ic_cat_microscope,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Микроскоп",
            labelEn = "Microscope",
            tags = listOf("наука", "исследования", "биология")
        ),
        IconEntry(
            key = "lucide:flask-conical",
            legacyKey = "flask-conical",
            drawableRes = R.drawable.ic_cat_flask_conical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Колба",
            labelEn = "Flask",
            tags = listOf("химия", "опыты", "лаборатория")
        ),
        IconEntry(
            key = "lucide:atom",
            legacyKey = "atom",
            drawableRes = R.drawable.ic_cat_atom,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Атом",
            labelEn = "Atom",
            tags = listOf("физика", "наука", "исследования")
        ),
        IconEntry(
            key = "lucide:languages",
            legacyKey = "languages",
            drawableRes = R.drawable.ic_cat_languages,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Языки",
            labelEn = "Languages",
            tags = listOf("перевод", "иностранные", "словарь", "english")
        ),
        IconEntry(
            key = "lucide:scroll",
            legacyKey = "scroll",
            drawableRes = R.drawable.ic_cat_scroll,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Свиток",
            labelEn = "Scroll",
            tags = listOf("история", "документ", "правила")
        ),
        IconEntry(
            key = "lucide:file-text",
            legacyKey = "file-text",
            drawableRes = R.drawable.ic_cat_file_text,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Конспект / Статья",
            labelEn = "Text File",
            tags = listOf("документ", "текст", "отчет")
        ),
        IconEntry(
            key = "lucide:notebook-pen",
            legacyKey = "notebook-pen",
            drawableRes = R.drawable.ic_cat_notebook_pen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Блокнот с ручкой",
            labelEn = "Notebook",
            tags = listOf("записи", "дневник", "планы")
        ),
        IconEntry(
            key = "lucide:clipboard-check",
            legacyKey = "clipboard-check",
            drawableRes = R.drawable.ic_cat_clipboard_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Чек-лист",
            labelEn = "Clipboard Check",
            tags = listOf("проверка", "задания", "тест")
        ),
        IconEntry(
            key = "lucide:presentation",
            legacyKey = "presentation",
            drawableRes = R.drawable.ic_cat_presentation,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Презентация",
            labelEn = "Presentation",
            tags = listOf("доклад", "слайды", "выступление")
        ),
        IconEntry(
            key = "lucide:brain",
            legacyKey = "brain",
            drawableRes = R.drawable.ic_cat_brain,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Мозг / Память",
            labelEn = "Brain",
            tags = listOf("мышление", "интеллект", "память", "anki")
        ),
        IconEntry(
            key = "lucide:briefcase",
            legacyKey = "briefcase",
            drawableRes = R.drawable.ic_cat_briefcase,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Портфель / Работа",
            labelEn = "Briefcase",
            tags = listOf("карьера", "бизнес", "офис")
        ),
        IconEntry(
            key = "lucide:laptop",
            legacyKey = "laptop",
            drawableRes = R.drawable.ic_cat_laptop,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Ноутбук",
            labelEn = "Laptop",
            tags = listOf("компьютер", "работа", "ноут")
        ),
        IconEntry(
            key = "lucide:monitor",
            legacyKey = "monitor",
            drawableRes = R.drawable.ic_cat_monitor,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Монитор",
            labelEn = "Monitor",
            tags = listOf("экран", "рабочее место")
        ),
        IconEntry(
            key = "lucide:phone",
            legacyKey = "phone",
            drawableRes = R.drawable.ic_cat_phone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Телефон",
            labelEn = "Phone",
            tags = listOf("звонки", "связь")
        ),
        IconEntry(
            key = "lucide:mail",
            legacyKey = "mail",
            drawableRes = R.drawable.ic_cat_mail,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Почта",
            labelEn = "Mail",
            tags = listOf("письма", "email", "сообщения")
        ),
        IconEntry(
            key = "lucide:calendar",
            legacyKey = "calendar",
            drawableRes = R.drawable.ic_cat_calendar,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Календарь",
            labelEn = "Calendar",
            tags = listOf("дедлайны", "планирование", "встречи")
        ),
        IconEntry(
            key = "lucide:calendar-days",
            legacyKey = "calendar-days",
            drawableRes = R.drawable.ic_cat_calendar_days,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Дни календаря",
            labelEn = "Calendar Days",
            tags = listOf("расписание", "дни")
        ),
        IconEntry(
            key = "lucide:clock",
            legacyKey = "clock",
            drawableRes = R.drawable.ic_cat_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Часы",
            labelEn = "Clock",
            tags = listOf("время", "тайм-менеджмент")
        ),
        IconEntry(
            key = "lucide:check-square",
            legacyKey = "check-square",
            drawableRes = R.drawable.ic_cat_check_square,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Задача",
            labelEn = "Check Square",
            tags = listOf("выполнено", "список")
        ),
        IconEntry(
            key = "lucide:folder",
            legacyKey = "folder",
            drawableRes = R.drawable.ic_cat_folder,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Папка",
            labelEn = "Folder",
            tags = listOf("файлы", "документы", "проект")
        ),
        IconEntry(
            key = "lucide:folder-open",
            legacyKey = "folder-open",
            drawableRes = R.drawable.ic_cat_folder_open,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Открытая папка",
            labelEn = "Open Folder",
            tags = listOf("директория", "файлы")
        ),
        IconEntry(
            key = "lucide:hard-drive",
            legacyKey = "hard-drive",
            drawableRes = R.drawable.ic_cat_hard_drive,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Диск",
            labelEn = "Hard Drive",
            tags = listOf("хранилище", "архив")
        ),
        IconEntry(
            key = "lucide:printer",
            legacyKey = "printer",
            drawableRes = R.drawable.ic_cat_printer,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Принтер",
            labelEn = "Printer",
            tags = listOf("печать", "документы")
        ),
        IconEntry(
            key = "lucide:newspaper",
            legacyKey = "newspaper",
            drawableRes = R.drawable.ic_cat_newspaper,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Новости / Пресса",
            labelEn = "Newspaper",
            tags = listOf("статьи", "медиа")
        ),
        IconEntry(
            key = "lucide:badge",
            legacyKey = "badge",
            drawableRes = R.drawable.ic_cat_badge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Бейдж / Должность",
            labelEn = "Badge",
            tags = listOf("статус", "пропуск")
        ),
        IconEntry(
            key = "lucide:building-2",
            legacyKey = "building-2",
            drawableRes = R.drawable.ic_cat_building_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Офис / Здание",
            labelEn = "Building",
            tags = listOf("компания", "корпорация")
        ),
        IconEntry(
            key = "lucide:compass",
            legacyKey = "compass",
            drawableRes = R.drawable.ic_cat_compass,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Компас / Стратегия",
            labelEn = "Compass",
            tags = listOf("направление", "ориентир")
        ),
        IconEntry(
            key = "lucide:target",
            legacyKey = "target",
            drawableRes = R.drawable.ic_cat_target,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.WORK,
            labelRu = "Цель",
            labelEn = "Target",
            tags = listOf("мишень", "фокус", "kpi")
        ),
        IconEntry(
            key = "lucide:code-2",
            legacyKey = "code-2",
            drawableRes = R.drawable.ic_cat_code_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Исходный код",
            labelEn = "Code",
            tags = listOf("программирование", "скрипт", "разработка")
        ),
        IconEntry(
            key = "lucide:terminal",
            legacyKey = "terminal",
            drawableRes = R.drawable.ic_cat_terminal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Терминал / Консоль",
            labelEn = "Terminal",
            tags = listOf("командная строка", "bash", "powershell")
        ),
        IconEntry(
            key = "lucide:database",
            legacyKey = "database",
            drawableRes = R.drawable.ic_cat_database,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "База данных",
            labelEn = "Database",
            tags = listOf("sql", "хранилище", "таблицы")
        ),
        IconEntry(
            key = "lucide:cpu",
            legacyKey = "cpu",
            drawableRes = R.drawable.ic_cat_cpu,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Процессор / Чип",
            labelEn = "CPU",
            tags = listOf("hardware", "железо", "архитектура")
        ),
        IconEntry(
            key = "lucide:git-branch",
            legacyKey = "git-branch",
            drawableRes = R.drawable.ic_cat_git_branch,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Ветка",
            labelEn = "Git Branch",
            tags = listOf("репозиторий", "ветка", "коммит")
        ),
        IconEntry(
            key = "lucide:git-commit-horizontal",
            legacyKey = "git-commit-horizontal",
            drawableRes = R.drawable.ic_cat_git_commit_horizontal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Коммит",
            labelEn = "Git Commit",
            tags = listOf("фиксация", "изменения")
        ),
        IconEntry(
            key = "lucide:git-pull-request",
            legacyKey = "git-pull-request",
            drawableRes = R.drawable.ic_cat_git_pull_request,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Pull Request",
            labelEn = "Pull Request",
            tags = listOf("мердж", "код-ревью")
        ),
        IconEntry(
            key = "lucide:server",
            legacyKey = "server",
            drawableRes = R.drawable.ic_cat_server,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Сервер",
            labelEn = "Server",
            tags = listOf("бэкенд", "хостинг", "инфраструктура")
        ),
        IconEntry(
            key = "lucide:bug",
            legacyKey = "bug",
            drawableRes = R.drawable.ic_cat_bug,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Баг / Отладка",
            labelEn = "Bug",
            tags = listOf("ошибка", "дебаг", "исправление")
        ),
        IconEntry(
            key = "lucide:binary",
            legacyKey = "binary",
            drawableRes = R.drawable.ic_cat_binary,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Двоичный код",
            labelEn = "Binary",
            tags = listOf("алгоритмы", "биты")
        ),
        IconEntry(
            key = "lucide:file-code",
            legacyKey = "file-code",
            drawableRes = R.drawable.ic_cat_file_code,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Файл кода",
            labelEn = "Code File",
            tags = listOf("исходник", "модуль")
        ),
        IconEntry(
            key = "lucide:blocks",
            legacyKey = "blocks",
            drawableRes = R.drawable.ic_cat_blocks,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Модули / Блоки",
            labelEn = "Blocks",
            tags = listOf("компоненты", "архитектура")
        ),
        IconEntry(
            key = "lucide:workflow",
            legacyKey = "workflow",
            drawableRes = R.drawable.ic_cat_workflow,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Пайплайн / Workflow",
            labelEn = "Workflow",
            tags = listOf("процессы", "ci/cd")
        ),
        IconEntry(
            key = "lucide:network",
            legacyKey = "network",
            drawableRes = R.drawable.ic_cat_network,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Сеть / Граф",
            labelEn = "Network",
            tags = listOf("интернет", "соединение", "топология")
        ),
        IconEntry(
            key = "lucide:cable",
            legacyKey = "cable",
            drawableRes = R.drawable.ic_cat_cable,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Кабель",
            labelEn = "Cable",
            tags = listOf("подключение", "провода")
        ),
        IconEntry(
            key = "lucide:qr-code",
            legacyKey = "qr-code",
            drawableRes = R.drawable.ic_cat_qr_code,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "QR-код",
            labelEn = "QR Code",
            tags = listOf("сканирование", "ссылка")
        ),
        IconEntry(
            key = "lucide:heart",
            legacyKey = "heart",
            drawableRes = R.drawable.ic_cat_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Сердце",
            labelEn = "Heart",
            tags = listOf("здоровье", "кардио", "жизнь")
        ),
        IconEntry(
            key = "lucide:activity",
            legacyKey = "activity",
            drawableRes = R.drawable.ic_cat_activity,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Пульс / Активность",
            labelEn = "Pulse",
            tags = listOf("ритм", "экг", "кардио")
        ),
        IconEntry(
            key = "lucide:pill",
            legacyKey = "pill",
            drawableRes = R.drawable.ic_cat_pill,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Таблетка / Витамины",
            labelEn = "Pill",
            tags = listOf("лекарства", "бады", "аптека")
        ),
        IconEntry(
            key = "lucide:stethoscope",
            legacyKey = "stethoscope",
            drawableRes = R.drawable.ic_cat_stethoscope,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Стетоскоп",
            labelEn = "Stethoscope",
            tags = listOf("врач", "чекап", "медицина")
        ),
        IconEntry(
            key = "lucide:thermometer",
            legacyKey = "thermometer",
            drawableRes = R.drawable.ic_cat_thermometer,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Термометр",
            labelEn = "Thermometer",
            tags = listOf("температура", "здоровье")
        ),
        IconEntry(
            key = "lucide:shield",
            legacyKey = "shield",
            drawableRes = R.drawable.ic_cat_shield,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Иммунитет / Защита",
            labelEn = "Shield",
            tags = listOf("профилактика", "защита")
        ),
        IconEntry(
            key = "lucide:shield-check",
            legacyKey = "shield-check",
            drawableRes = R.drawable.ic_cat_shield_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Проверено / Здоров",
            labelEn = "Shield Check",
            tags = listOf("безопасность", "норма")
        ),
        IconEntry(
            key = "lucide:smile",
            legacyKey = "smile",
            drawableRes = R.drawable.ic_cat_smile,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Улыбка / Настроение",
            labelEn = "Smile",
            tags = listOf("психика", "радость", "позитив")
        ),
        IconEntry(
            key = "lucide:eye",
            legacyKey = "eye",
            drawableRes = R.drawable.ic_cat_eye,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Зрение / Глаза",
            labelEn = "Eye",
            tags = listOf("зарядка для глаз", "осмотр")
        ),
        IconEntry(
            key = "lucide:cross",
            legacyKey = "cross",
            drawableRes = R.drawable.ic_cat_cross,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Медицинский крест",
            labelEn = "Cross",
            tags = listOf("первая помощь", "клиника")
        ),
        IconEntry(
            key = "lucide:bandage",
            legacyKey = "bandage",
            drawableRes = R.drawable.ic_cat_bandage,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Пластырь",
            labelEn = "Bandage",
            tags = listOf("заживление", "восстановление")
        ),
        IconEntry(
            key = "lucide:syringe",
            legacyKey = "syringe",
            drawableRes = R.drawable.ic_cat_syringe,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Прививка / Анализ",
            labelEn = "Syringe",
            tags = listOf("вакцина", "анализы")
        ),
        IconEntry(
            key = "lucide:dna",
            legacyKey = "dna",
            drawableRes = R.drawable.ic_cat_dna,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "ДНК / Генетика",
            labelEn = "DNA",
            tags = listOf("биология", "тело")
        ),
        IconEntry(
            key = "lucide:sparkle",
            legacyKey = "sparkle",
            drawableRes = R.drawable.ic_cat_sparkle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Свежесть / Тонус",
            labelEn = "Sparkle",
            tags = listOf("энергия", "чистота")
        ),
        IconEntry(
            key = "lucide:weight",
            legacyKey = "weight",
            drawableRes = R.drawable.ic_cat_weight,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Весы",
            labelEn = "Weight",
            tags = listOf("масса тела", "взвешивание", "форма")
        ),
        IconEntry(
            key = "lucide:dumbbell",
            legacyKey = "dumbbell",
            drawableRes = R.drawable.ic_cat_dumbbell,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Гантель / Зал",
            labelEn = "Dumbbell",
            tags = listOf("силовые", "тренировка", "мышцы", "фитнес")
        ),
        IconEntry(
            key = "lucide:bike",
            legacyKey = "bike",
            drawableRes = R.drawable.ic_cat_bike,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Велосипед",
            labelEn = "Bike",
            tags = listOf("вело", "кардио", "поездка")
        ),
        IconEntry(
            key = "lucide:footprints",
            legacyKey = "footprints",
            drawableRes = R.drawable.ic_cat_footprints,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Шаги / Ходьба",
            labelEn = "Footprints",
            tags = listOf("прогулка", "10000 шагов", "дистанция")
        ),
        IconEntry(
            key = "lucide:flame",
            legacyKey = "flame",
            drawableRes = R.drawable.ic_cat_flame,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Калории / Огонь",
            labelEn = "Flame",
            tags = listOf("сжигание", "интенсивность", "стрик")
        ),
        IconEntry(
            key = "lucide:trophy",
            legacyKey = "trophy",
            drawableRes = R.drawable.ic_cat_trophy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Кубок / Победа",
            labelEn = "Trophy",
            tags = listOf("соревнование", "достижение", "чемпион")
        ),
        IconEntry(
            key = "lucide:medal",
            legacyKey = "medal",
            drawableRes = R.drawable.ic_cat_medal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Медаль",
            labelEn = "Medal",
            tags = listOf("награда", "результат")
        ),
        IconEntry(
            key = "lucide:timer",
            legacyKey = "timer",
            drawableRes = R.drawable.ic_cat_timer,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Секундомер",
            labelEn = "Timer",
            tags = listOf("интервалы", "спринт", "время")
        ),
        IconEntry(
            key = "lucide:swords",
            legacyKey = "swords",
            drawableRes = R.drawable.ic_cat_swords,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Единоборства",
            labelEn = "Swords",
            tags = listOf("бокс", "борьба", "спарринг")
        ),
        IconEntry(
            key = "lucide:flag",
            legacyKey = "flag",
            drawableRes = R.drawable.ic_cat_flag,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Финиш / Флаг",
            labelEn = "Flag",
            tags = listOf("цель", "старт", "марафон")
        ),
        IconEntry(
            key = "lucide:award",
            legacyKey = "award",
            drawableRes = R.drawable.ic_cat_award,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Грамота",
            labelEn = "Award",
            tags = listOf("достижения", "спорт")
        ),
        IconEntry(
            key = "lucide:zap",
            legacyKey = "zap",
            drawableRes = R.drawable.ic_cat_zap,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Энергия / Зарядка",
            labelEn = "Zap",
            tags = listOf("разминка", "бодрость")
        ),
        IconEntry(
            key = "lucide:scale",
            legacyKey = "scale",
            drawableRes = R.drawable.ic_cat_scale,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Баланс / Равновесие",
            labelEn = "Scale",
            tags = listOf("йога", "растяжка")
        ),
        IconEntry(
            key = "lucide:mountain",
            legacyKey = "mountain",
            drawableRes = R.drawable.ic_cat_mountain,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Горы / Поход",
            labelEn = "Mountain",
            tags = listOf("хайкинг", "треккинг")
        ),
        IconEntry(
            key = "lucide:navigation",
            legacyKey = "navigation",
            drawableRes = R.drawable.ic_cat_navigation,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Навигация / Маршрут",
            labelEn = "Navigation",
            tags = listOf("пробежка", "трек")
        ),
        IconEntry(
            key = "lucide:sun",
            legacyKey = "sun",
            drawableRes = R.drawable.ic_cat_sun,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Утро / Солнце",
            labelEn = "Sun",
            tags = listOf("подъем", "утренний ритуал")
        ),
        IconEntry(
            key = "lucide:sunrise",
            legacyKey = "sunrise",
            drawableRes = R.drawable.ic_cat_sunrise,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Рассвет",
            labelEn = "Sunrise",
            tags = listOf("ранний подъем", "магия утра")
        ),
        IconEntry(
            key = "lucide:sunset",
            legacyKey = "sunset",
            drawableRes = R.drawable.ic_cat_sunset,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Закат",
            labelEn = "Sunset",
            tags = listOf("вечер", "итоги дня")
        ),
        IconEntry(
            key = "lucide:alarm-clock",
            legacyKey = "alarm-clock",
            drawableRes = R.drawable.ic_cat_alarm_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Будильник",
            labelEn = "Alarm Clock",
            tags = listOf("пробуждение", "время")
        ),
        IconEntry(
            key = "lucide:calendar-clock",
            legacyKey = "calendar-clock",
            drawableRes = R.drawable.ic_cat_calendar_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Расписание",
            labelEn = "Schedule",
            tags = listOf("режим", "тайминг")
        ),
        IconEntry(
            key = "lucide:hourglass",
            legacyKey = "hourglass",
            drawableRes = R.drawable.ic_cat_hourglass,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Песочные часы",
            labelEn = "Hourglass",
            tags = listOf("фокус", "помодоро", "терпение")
        ),
        IconEntry(
            key = "lucide:check-circle-2",
            legacyKey = "check-circle-2",
            drawableRes = R.drawable.ic_cat_check_circle_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Выполнено / Галочка",
            labelEn = "Check Circle",
            tags = listOf("привычка", "отметка")
        ),
        IconEntry(
            key = "lucide:list-todo",
            legacyKey = "list-todo",
            drawableRes = R.drawable.ic_cat_list_todo,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Список привычек",
            labelEn = "Habit List",
            tags = listOf("рутина", "план на день")
        ),
        IconEntry(
            key = "lucide:bell",
            legacyKey = "bell",
            drawableRes = R.drawable.ic_cat_bell,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Колокольчик",
            labelEn = "Notification",
            tags = listOf("напоминание", "уведомление")
        ),
        IconEntry(
            key = "lucide:repeat",
            legacyKey = "repeat",
            drawableRes = R.drawable.ic_cat_repeat,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Повторение",
            labelEn = "Repeat",
            tags = listOf("регулярность", "цикл")
        ),
        IconEntry(
            key = "lucide:refresh-cw",
            legacyKey = "refresh-cw",
            drawableRes = R.drawable.ic_cat_refresh_cw,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Обновление / Цикл",
            labelEn = "Refresh",
            tags = listOf("ежедневно", "привыкание")
        ),
        IconEntry(
            key = "lucide:sparkles",
            legacyKey = "sparkles",
            drawableRes = R.drawable.ic_cat_sparkles,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Сияние / Магия",
            labelEn = "Sparkles",
            tags = listOf("чистота", "вдохновение")
        ),
        IconEntry(
            key = "lucide:sun-medium",
            legacyKey = "sun-medium",
            drawableRes = R.drawable.ic_cat_sun_medium,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "День",
            labelEn = "Day Sun",
            tags = listOf("дневные дела", "активность")
        ),
        IconEntry(
            key = "lucide:apple",
            legacyKey = "apple",
            drawableRes = R.drawable.ic_cat_apple,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Яблоко / Фрукты",
            labelEn = "Apple",
            tags = listOf("здоровое питание", "витамины", "перекус")
        ),
        IconEntry(
            key = "lucide:utensils",
            legacyKey = "utensils",
            drawableRes = R.drawable.ic_cat_utensils,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Приборы / Еда",
            labelEn = "Utensils",
            tags = listOf("обед", "завтрак", "ужин", "прием пищи")
        ),
        IconEntry(
            key = "lucide:coffee",
            legacyKey = "coffee",
            drawableRes = R.drawable.ic_cat_coffee,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Кофе",
            labelEn = "Coffee",
            tags = listOf("эспрессо", "капучино", "бодрость")
        ),
        IconEntry(
            key = "lucide:cup-soda",
            legacyKey = "cup-soda",
            drawableRes = R.drawable.ic_cat_cup_soda,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Напиток",
            labelEn = "Drink",
            tags = listOf("сок", "лимонад")
        ),
        IconEntry(
            key = "lucide:cookie",
            legacyKey = "cookie",
            drawableRes = R.drawable.ic_cat_cookie,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Печенье / Сладкое",
            labelEn = "Cookie",
            tags = listOf("десерт", "сахар", "ограничение")
        ),
    )

    private fun getPart2(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:droplet",
            legacyKey = "droplet",
            drawableRes = R.drawable.ic_cat_droplet,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Вода / Капля",
            labelEn = "Water Droplet",
            tags = listOf("гидратация", "пить воду", "2 литра")
        ),
        IconEntry(
            key = "lucide:salad",
            legacyKey = "salad",
            drawableRes = R.drawable.ic_cat_salad,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Салат / Овощи",
            labelEn = "Salad",
            tags = listOf("клетчатка", "пп", "зелень")
        ),
        IconEntry(
            key = "lucide:pizza",
            legacyKey = "pizza",
            drawableRes = R.drawable.ic_cat_pizza,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Пицца / Читмил",
            labelEn = "Pizza",
            tags = listOf("фастфуд", "читмил")
        ),
        IconEntry(
            key = "lucide:wine",
            legacyKey = "wine",
            drawableRes = R.drawable.ic_cat_wine,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Бокал вина",
            labelEn = "Wine",
            tags = listOf("алкоголь", "ограничение")
        ),
        IconEntry(
            key = "lucide:beer",
            legacyKey = "beer",
            drawableRes = R.drawable.ic_cat_beer,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Пиво",
            labelEn = "Beer",
            tags = listOf("бар", "алкоголь")
        ),
        IconEntry(
            key = "lucide:cake",
            legacyKey = "cake",
            drawableRes = R.drawable.ic_cat_cake,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Торт / Праздник",
            labelEn = "Cake",
            tags = listOf("сладкое", "день рождения")
        ),
        IconEntry(
            key = "lucide:soup",
            legacyKey = "soup",
            drawableRes = R.drawable.ic_cat_soup,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Суп / Горячее",
            labelEn = "Soup",
            tags = listOf("обед", "первое блюдо")
        ),
        IconEntry(
            key = "lucide:glass-water",
            legacyKey = "glass-water",
            drawableRes = R.drawable.ic_cat_glass_water,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Стакан воды",
            labelEn = "Glass of Water",
            tags = listOf("водный баланс", "утренний стакан")
        ),
        IconEntry(
            key = "lucide:moon",
            legacyKey = "moon",
            drawableRes = R.drawable.ic_cat_moon,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Луна / Ночь",
            labelEn = "Moon",
            tags = listOf("сон", "отбой", "ночной режим")
        ),
        IconEntry(
            key = "lucide:moon-star",
            legacyKey = "moon-star",
            drawableRes = R.drawable.ic_cat_moon_star,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Ночное небо",
            labelEn = "Moon & Star",
            tags = listOf("глубокий сон", "релакс")
        ),
        IconEntry(
            key = "lucide:bed",
            legacyKey = "bed",
            drawableRes = R.drawable.ic_cat_bed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Кровать",
            labelEn = "Bed",
            tags = listOf("спать", "ложиться вовремя")
        ),
        IconEntry(
            key = "lucide:bed-double",
            legacyKey = "bed-double",
            drawableRes = R.drawable.ic_cat_bed_double,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Двуспальная кровать",
            labelEn = "Double Bed",
            tags = listOf("отдых", "спальня")
        ),
        IconEntry(
            key = "lucide:cloud-moon",
            legacyKey = "cloud-moon",
            drawableRes = R.drawable.ic_cat_cloud_moon,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Облако и луна",
            labelEn = "Cloudy Moon",
            tags = listOf("вечер", "тишина")
        ),
        IconEntry(
            key = "lucide:cloud",
            legacyKey = "cloud",
            drawableRes = R.drawable.ic_cat_cloud,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Облако",
            labelEn = "Cloud",
            tags = listOf("мысли", "спокойствие")
        ),
        IconEntry(
            key = "lucide:stars",
            legacyKey = "stars",
            drawableRes = R.drawable.ic_cat_stars,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SLEEP,
            labelRu = "Звезды",
            labelEn = "Stars",
            tags = listOf("ночь", "мечты")
        ),
        IconEntry(
            key = "lucide:home",
            legacyKey = "home",
            drawableRes = R.drawable.ic_cat_home,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Дом",
            labelEn = "Home",
            tags = listOf("семья", "быт", "уют")
        ),
        IconEntry(
            key = "lucide:shopping-cart",
            legacyKey = "shopping-cart",
            drawableRes = R.drawable.ic_cat_shopping_cart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Тележка покупок",
            labelEn = "Shopping Cart",
            tags = listOf("магазин", "продукты")
        ),
        IconEntry(
            key = "lucide:shopping-bag",
            legacyKey = "shopping-bag",
            drawableRes = R.drawable.ic_cat_shopping_bag,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Пакет покупок",
            labelEn = "Shopping Bag",
            tags = listOf("шоппинг", "заказ")
        ),
        IconEntry(
            key = "lucide:trash-2",
            legacyKey = "trash-2",
            drawableRes = R.drawable.ic_cat_trash_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Мусорная корзина",
            labelEn = "Trash",
            tags = listOf("вынести мусор", "уборка", "расхламление")
        ),
        IconEntry(
            key = "lucide:wrench",
            legacyKey = "wrench",
            drawableRes = R.drawable.ic_cat_wrench,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Гаечный ключ",
            labelEn = "Wrench",
            tags = listOf("ремонт", "инструменты", "починка")
        ),
        IconEntry(
            key = "lucide:hammer",
            legacyKey = "hammer",
            drawableRes = R.drawable.ic_cat_hammer,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Молоток",
            labelEn = "Hammer",
            tags = listOf("строительство", "мастерская")
        ),
        IconEntry(
            key = "lucide:bath",
            legacyKey = "bath",
            drawableRes = R.drawable.ic_cat_bath,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Ванна / Душ",
            labelEn = "Bath",
            tags = listOf("гигиена", "уход за собой", "купание")
        ),
        IconEntry(
            key = "lucide:sprout",
            legacyKey = "sprout",
            drawableRes = R.drawable.ic_cat_sprout,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Росток / Цветы",
            labelEn = "Sprout",
            tags = listOf("растения", "полив", "сад")
        ),
        IconEntry(
            key = "lucide:cat",
            legacyKey = "cat",
            drawableRes = R.drawable.ic_cat_cat,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Кот",
            labelEn = "Cat",
            tags = listOf("питомцы", "кормление кота")
        ),
        IconEntry(
            key = "lucide:dog",
            legacyKey = "dog",
            drawableRes = R.drawable.ic_cat_dog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Собака",
            labelEn = "Dog",
            tags = listOf("выгул собаки", "питомец")
        ),
        IconEntry(
            key = "lucide:package",
            legacyKey = "package",
            drawableRes = R.drawable.ic_cat_package,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Посылка / Коробка",
            labelEn = "Package",
            tags = listOf("доставка", "почта")
        ),
        IconEntry(
            key = "lucide:key",
            legacyKey = "key",
            drawableRes = R.drawable.ic_cat_key,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Ключ",
            labelEn = "Key",
            tags = listOf("безопасность", "доступ")
        ),
        IconEntry(
            key = "lucide:door-closed",
            legacyKey = "door-closed",
            drawableRes = R.drawable.ic_cat_door_closed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Дверь",
            labelEn = "Door",
            tags = listOf("дом", "выход")
        ),
        IconEntry(
            key = "lucide:washing-machine",
            legacyKey = "washing-machine",
            drawableRes = R.drawable.ic_cat_washing_machine,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Стиральная машина",
            labelEn = "Washing Machine",
            tags = listOf("стирка", "белье")
        ),
        IconEntry(
            key = "lucide:armchair",
            legacyKey = "armchair",
            drawableRes = R.drawable.ic_cat_armchair,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Кресло / Отдых",
            labelEn = "Armchair",
            tags = listOf("чтение в кресле", "уют")
        ),
        IconEntry(
            key = "lucide:lamp",
            legacyKey = "lamp",
            drawableRes = R.drawable.ic_cat_lamp,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Лампа / Торшер",
            labelEn = "Lamp",
            tags = listOf("свет", "вечерний уют")
        ),
        IconEntry(
            key = "lucide:user",
            legacyKey = "user",
            drawableRes = R.drawable.ic_cat_user,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Пользователь / Личное",
            labelEn = "User",
            tags = listOf("я", "профиль", "индивидуальное")
        ),
        IconEntry(
            key = "lucide:users",
            legacyKey = "users",
            drawableRes = R.drawable.ic_cat_users,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Люди / Семья / Команда",
            labelEn = "Users",
            tags = listOf("друзья", "общение", "коллеги")
        ),
        IconEntry(
            key = "lucide:user-check",
            legacyKey = "user-check",
            drawableRes = R.drawable.ic_cat_user_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Знакомство / Контакт",
            labelEn = "User Check",
            tags = listOf("связь", "человек")
        ),
        IconEntry(
            key = "lucide:message-circle",
            legacyKey = "message-circle",
            drawableRes = R.drawable.ic_cat_message_circle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Диалог / Сообщение",
            labelEn = "Message Circle",
            tags = listOf("звонок другу", "чат")
        ),
        IconEntry(
            key = "lucide:message-square",
            legacyKey = "message-square",
            drawableRes = R.drawable.ic_cat_message_square,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Чат / Разговор",
            labelEn = "Message Square",
            tags = listOf("переписка", "ответы")
        ),
        IconEntry(
            key = "lucide:heart-handshake",
            legacyKey = "heart-handshake",
            drawableRes = R.drawable.ic_cat_heart_handshake,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Забота / Помощь",
            labelEn = "Care & Help",
            tags = listOf("благотворительность", "поддержка")
        ),
        IconEntry(
            key = "lucide:handshake",
            legacyKey = "handshake",
            drawableRes = R.drawable.ic_cat_handshake,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Рукопожатие",
            labelEn = "Handshake",
            tags = listOf("договоренность", "партнерство")
        ),
        IconEntry(
            key = "lucide:send",
            legacyKey = "send",
            drawableRes = R.drawable.ic_cat_send,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Отправить",
            labelEn = "Send",
            tags = listOf("сообщение", "письмо")
        ),
        IconEntry(
            key = "lucide:share-2",
            legacyKey = "share-2",
            drawableRes = R.drawable.ic_cat_share_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Поделиться",
            labelEn = "Share",
            tags = listOf("репост", "контент")
        ),
        IconEntry(
            key = "lucide:smile-plus",
            legacyKey = "smile-plus",
            drawableRes = R.drawable.ic_cat_smile_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Радость общения",
            labelEn = "Smile Plus",
            tags = listOf("комплименты", "эмоции")
        ),
        IconEntry(
            key = "lucide:party-popper",
            legacyKey = "party-popper",
            drawableRes = R.drawable.ic_cat_party_popper,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Праздник / Вечеринка",
            labelEn = "Party",
            tags = listOf("веселье", "встреча друзей")
        ),
        IconEntry(
            key = "lucide:gift",
            legacyKey = "gift",
            drawableRes = R.drawable.ic_cat_gift,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Подарок",
            labelEn = "Gift",
            tags = listOf("внимание", "сюрприз", "поздравление")
        ),
        IconEntry(
            key = "lucide:wallet",
            legacyKey = "wallet",
            drawableRes = R.drawable.ic_cat_wallet,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Кошелек",
            labelEn = "Wallet",
            tags = listOf("бюджет", "деньги", "накопления")
        ),
        IconEntry(
            key = "lucide:credit-card",
            legacyKey = "credit-card",
            drawableRes = R.drawable.ic_cat_credit_card,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Банковская карта",
            labelEn = "Credit Card",
            tags = listOf("расходы", "оплата")
        ),
        IconEntry(
            key = "lucide:coins",
            legacyKey = "coins",
            drawableRes = R.drawable.ic_cat_coins,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Монеты / Сбережения",
            labelEn = "Coins",
            tags = listOf("мелочь", "инвестиции")
        ),
        IconEntry(
            key = "lucide:banknote",
            legacyKey = "banknote",
            drawableRes = R.drawable.ic_cat_banknote,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Купюра / Доход",
            labelEn = "Banknote",
            tags = listOf("зарплата", "наличные")
        ),
        IconEntry(
            key = "lucide:piggy-bank",
            legacyKey = "piggy-bank",
            drawableRes = R.drawable.ic_cat_piggy_bank,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Копилка",
            labelEn = "Piggy Bank",
            tags = listOf("сбережения", "финансовая подушка")
        ),
        IconEntry(
            key = "lucide:trending-up",
            legacyKey = "trending-up",
            drawableRes = R.drawable.ic_cat_trending_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Рост / Инвестиции",
            labelEn = "Trending Up",
            tags = listOf("прибыль", "акции")
        ),
        IconEntry(
            key = "lucide:receipt",
            legacyKey = "receipt",
            drawableRes = R.drawable.ic_cat_receipt,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Чек / Учет расходов",
            labelEn = "Receipt",
            tags = listOf("бухгалтерия", "траты")
        ),
        IconEntry(
            key = "lucide:tag",
            legacyKey = "tag",
            drawableRes = R.drawable.ic_cat_tag,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Ценник / Скидки",
            labelEn = "Tag",
            tags = listOf("покупки", "экономия")
        ),
        IconEntry(
            key = "lucide:dollar-sign",
            legacyKey = "dollar-sign",
            drawableRes = R.drawable.ic_cat_dollar_sign,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Доллар / Валюта",
            labelEn = "Currency",
            tags = listOf("деньги", "финансы")
        ),
        IconEntry(
            key = "lucide:percent",
            legacyKey = "percent",
            drawableRes = R.drawable.ic_cat_percent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Проценты / Ставка",
            labelEn = "Percent",
            tags = listOf("кэшбэк", "депозит")
        ),
        IconEntry(
            key = "lucide:shopping-basket",
            legacyKey = "shopping-basket",
            drawableRes = R.drawable.ic_cat_shopping_basket,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Корзина",
            labelEn = "Basket",
            tags = listOf("супермаркет", "бюджет")
        ),
        IconEntry(
            key = "lucide:landmark",
            legacyKey = "landmark",
            drawableRes = R.drawable.ic_cat_landmark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Банк",
            labelEn = "Bank",
            tags = listOf("счет", "вклад")
        ),
        IconEntry(
            key = "lucide:palette",
            legacyKey = "palette",
            drawableRes = R.drawable.ic_cat_palette,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Палитра / Живопись",
            labelEn = "Palette",
            tags = listOf("рисование", "скетчинг", "арт")
        ),
        IconEntry(
            key = "lucide:brush",
            legacyKey = "brush",
            drawableRes = R.drawable.ic_cat_brush,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Кисть",
            labelEn = "Brush",
            tags = listOf("краски", "холст")
        ),
        IconEntry(
            key = "lucide:camera",
            legacyKey = "camera",
            drawableRes = R.drawable.ic_cat_camera,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Камера / Фото",
            labelEn = "Camera",
            tags = listOf("фотография", "съемка")
        ),
        IconEntry(
            key = "lucide:music",
            legacyKey = "music",
            drawableRes = R.drawable.ic_cat_music,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Музыка / Ноты",
            labelEn = "Music",
            tags = listOf("инструменты", "сочинение")
        ),
        IconEntry(
            key = "lucide:headphones",
            legacyKey = "headphones",
            drawableRes = R.drawable.ic_cat_headphones,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Наушники / Подкасты",
            labelEn = "Headphones",
            tags = listOf("слушать музыку", "аудиокниги")
        ),
        IconEntry(
            key = "lucide:mic",
            legacyKey = "mic",
            drawableRes = R.drawable.ic_cat_mic,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Микрофон / Вокал",
            labelEn = "Microphone",
            tags = listOf("пение", "подкаст", "голос")
        ),
        IconEntry(
            key = "lucide:video",
            legacyKey = "video",
            drawableRes = R.drawable.ic_cat_video,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Видеокамера",
            labelEn = "Video",
            tags = listOf("youtube", "монтаж")
        ),
        IconEntry(
            key = "lucide:film",
            legacyKey = "film",
            drawableRes = R.drawable.ic_cat_film,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Кино / Фильмы",
            labelEn = "Film",
            tags = listOf("просмотр фильмов", "кинематограф")
        ),
        IconEntry(
            key = "lucide:gamepad-2",
            legacyKey = "gamepad-2",
            drawableRes = R.drawable.ic_cat_gamepad_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Геймпад / Игры",
            labelEn = "Gamepad",
            tags = listOf("игры", "отдых")
        ),
        IconEntry(
            key = "lucide:scissors",
            legacyKey = "scissors",
            drawableRes = R.drawable.ic_cat_scissors,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Ножницы / Хендмейд",
            labelEn = "Scissors",
            tags = listOf("рукоделие", "крафт")
        ),
        IconEntry(
            key = "lucide:wand-2",
            legacyKey = "wand-2",
            drawableRes = R.drawable.ic_cat_wand_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Магия / Волшебство",
            labelEn = "Magic Wand",
            tags = listOf("творчество", "креатив")
        ),
        IconEntry(
            key = "lucide:guitar",
            legacyKey = "guitar",
            drawableRes = R.drawable.ic_cat_guitar,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Гитара",
            labelEn = "Guitar",
            tags = listOf("игра на гитаре", "аккорды")
        ),
        IconEntry(
            key = "lucide:piano",
            legacyKey = "piano",
            drawableRes = R.drawable.ic_cat_piano,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Фортепиано",
            labelEn = "Piano",
            tags = listOf("клавиши", "музыкальная школа")
        ),
        IconEntry(
            key = "lucide:globe",
            legacyKey = "globe",
            drawableRes = R.drawable.ic_cat_globe,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Глобус / Мир",
            labelEn = "Globe",
            tags = listOf("путешествия", "география")
        ),
        IconEntry(
            key = "lucide:plane",
            legacyKey = "plane",
            drawableRes = R.drawable.ic_cat_plane,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Самолет / Перелет",
            labelEn = "Plane",
            tags = listOf("рейс", "отпуск")
        ),
        IconEntry(
            key = "lucide:map",
            legacyKey = "map",
            drawableRes = R.drawable.ic_cat_map,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Карта",
            labelEn = "Map",
            tags = listOf("маршрут", "навигация")
        ),
        IconEntry(
            key = "lucide:map-pin",
            legacyKey = "map-pin",
            drawableRes = R.drawable.ic_cat_map_pin,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Метка на карте",
            labelEn = "Map Pin",
            tags = listOf("место", "локация")
        ),
        IconEntry(
            key = "lucide:mountain-snow",
            legacyKey = "mountain-snow",
            drawableRes = R.drawable.ic_cat_mountain_snow,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Снежные горы",
            labelEn = "Snow Mountains",
            tags = listOf("альпинизм", "лыжи")
        ),
        IconEntry(
            key = "lucide:tree-pine",
            legacyKey = "tree-pine",
            drawableRes = R.drawable.ic_cat_tree_pine,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Ель / Лес",
            labelEn = "Pine Tree",
            tags = listOf("природа", "прогулка в лесу")
        ),
        IconEntry(
            key = "lucide:trees",
            legacyKey = "trees",
            drawableRes = R.drawable.ic_cat_trees,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Деревья / Парк",
            labelEn = "Trees",
            tags = listOf("экология", "свежий воздух")
        ),
        IconEntry(
            key = "lucide:umbrella",
            legacyKey = "umbrella",
            drawableRes = R.drawable.ic_cat_umbrella,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Зонт / Погода",
            labelEn = "Umbrella",
            tags = listOf("дождь", "прогноз")
        ),
        IconEntry(
            key = "lucide:car",
            legacyKey = "car",
            drawableRes = R.drawable.ic_cat_car,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Автомобиль",
            labelEn = "Car",
            tags = listOf("вождение", "поездка")
        ),
        IconEntry(
            key = "lucide:train",
            legacyKey = "train",
            drawableRes = R.drawable.ic_cat_train,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Поезд",
            labelEn = "Train",
            tags = listOf("жд", "путешествие поездом")
        ),
        IconEntry(
            key = "lucide:ship",
            legacyKey = "ship",
            drawableRes = R.drawable.ic_cat_ship,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Корабль / Круиз",
            labelEn = "Ship",
            tags = listOf("море", "плавание")
        ),
        IconEntry(
            key = "lucide:anchor",
            legacyKey = "anchor",
            drawableRes = R.drawable.ic_cat_anchor,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Якорь",
            labelEn = "Anchor",
            tags = listOf("причал", "надежность")
        ),
        IconEntry(
            key = "lucide:tent",
            legacyKey = "tent",
            drawableRes = R.drawable.ic_cat_tent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Палатка / Кемпинг",
            labelEn = "Tent",
            tags = listOf("поход", "ночевка на природе")
        ),
        IconEntry(
            key = "lucide:luggage",
            legacyKey = "luggage",
            drawableRes = R.drawable.ic_cat_luggage,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Чемодан / Багаж",
            labelEn = "Luggage",
            tags = listOf("сбор вещей", "поездка")
        ),
        IconEntry(
            key = "lucide:lightbulb",
            legacyKey = "lightbulb",
            drawableRes = R.drawable.ic_cat_lightbulb,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Лампочка / Идея",
            labelEn = "Lightbulb",
            tags = listOf("инсайт", "мысль", "творчество")
        ),
        IconEntry(
            key = "lucide:puzzle",
            legacyKey = "puzzle",
            drawableRes = R.drawable.ic_cat_puzzle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Пазл / Логика",
            labelEn = "Puzzle",
            tags = listOf("загадки", "мышление")
        ),
        IconEntry(
            key = "lucide:glasses",
            legacyKey = "glasses",
            drawableRes = R.drawable.ic_cat_glasses,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Очки / Чтение",
            labelEn = "Glasses",
            tags = listOf("внимательность", "интеллект")
        ),
        IconEntry(
            key = "lucide:gem",
            legacyKey = "gem",
            drawableRes = R.drawable.ic_cat_gem,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Алмаз / Ценность",
            labelEn = "Gem",
            tags = listOf("саморазвитие", "качество")
        ),
        IconEntry(
            key = "lucide:flower-2",
            legacyKey = "flower-2",
            drawableRes = R.drawable.ic_cat_flower_2,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Цветок / Медитация",
            labelEn = "Flower",
            tags = listOf("спокойствие", "гармония")
        ),
        IconEntry(
            key = "lucide:feather",
            legacyKey = "feather",
            drawableRes = R.drawable.ic_cat_feather,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Перо / Писательство",
            labelEn = "Feather",
            tags = listOf("эссе", "блог", "статьи")
        ),
        IconEntry(
            key = "lucide:milestone",
            legacyKey = "milestone",
            drawableRes = R.drawable.ic_cat_milestone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Веха / Этап",
            labelEn = "Milestone",
            tags = listOf("цели", "прогресс")
        ),
        IconEntry(
            key = "lucide:focus",
            legacyKey = "focus",
            drawableRes = R.drawable.ic_cat_focus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Фокус / Концентрация",
            labelEn = "Focus",
            tags = listOf("глубокая работа", "внимание")
        ),
        IconEntry(
            key = "lucide:scan-face",
            legacyKey = "scan-face",
            drawableRes = R.drawable.ic_cat_scan_face,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Лицо / Осознанность",
            labelEn = "Mindfulness",
            tags = listOf("эмоции", "рефлексия")
        ),
        IconEntry(
            key = "lucide:badge-check",
            legacyKey = "badge-check",
            drawableRes = R.drawable.ic_cat_badge_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Знак качества",
            labelEn = "Verified",
            tags = listOf("навык освоен", "успех")
        ),
        IconEntry(
            key = "lucide:crown",
            legacyKey = "crown",
            drawableRes = R.drawable.ic_cat_crown,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Корона / Лидерство",
            labelEn = "Crown",
            tags = listOf("уверенность", "достижения")
        ),
        IconEntry(
            key = "lucide:star",
            legacyKey = "star",
            drawableRes = R.drawable.ic_cat_star,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Звезда / Избранное",
            labelEn = "Star",
            tags = listOf("лучшее", "топ")
        ),
        IconEntry(
            key = "lucide:rocket",
            legacyKey = "rocket",
            drawableRes = R.drawable.ic_cat_rocket,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Ракета / Старт",
            labelEn = "Rocket",
            tags = listOf("запуск", "прорыв", "быстрый старт")
        ),
        IconEntry(
            key = "lucide:shield-alert",
            legacyKey = "shield-alert",
            drawableRes = R.drawable.ic_cat_shield_alert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.MIND,
            labelRu = "Самоконтроль",
            labelEn = "Self-Control",
            tags = listOf("дисциплина", "стоп-привычка")
        ),
        IconEntry(
            key = "lucide:a-large-small",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_a_large_small,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "A Large Small",
            labelEn = "A Large Small",
            tags = listOf("formatting", "font size", "letter", "a-large-small", "a large small")
        ),
        IconEntry(
            key = "lucide:accessibility",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_accessibility,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Accessibility",
            labelEn = "Accessibility",
            tags = listOf("wheelchair", "accessibility", "disability", "dda", "disabled")
        ),
        IconEntry(
            key = "lucide:ad",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ad,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Ad",
            labelEn = "Ad",
            tags = listOf("brand", "ad", "advert", "campaign", "commercial", "affiliate")
        ),
        IconEntry(
            key = "lucide:air-vent",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_air_vent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Air Vent",
            labelEn = "Air Vent",
            tags = listOf("cooling", "central air", "air-vent", "climate-control", "air conditioner", "ac", "air vent")
        ),
    )

    private fun getPart3(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:airplay",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_airplay,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Airplay",
            labelEn = "Airplay",
            tags = listOf("airplay", "mirroring", "stream", "cast", "monitor", "screen")
        ),
        IconEntry(
            key = "lucide:alarm-clock-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_alarm_clock_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Alarm Clock Check",
            labelEn = "Alarm Clock Check",
            tags = listOf("wake-up", "alarm-clock-check", "reminder", "alarm clock check")
        ),
        IconEntry(
            key = "lucide:alarm-clock-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_alarm_clock_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Alarm Clock Minus",
            labelEn = "Alarm Clock Minus",
            tags = listOf("unschedule", "reminder", "alarm clock minus", "alarm-clock-minus")
        ),
        IconEntry(
            key = "lucide:alarm-clock-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_alarm_clock_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Alarm Clock Off",
            labelEn = "Alarm Clock Off",
            tags = listOf("silence", "silent", "muted", "alarm clock off", "alarm-clock-off", "disarmed")
        ),
        IconEntry(
            key = "lucide:alarm-clock-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_alarm_clock_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Alarm Clock Plus",
            labelEn = "Alarm Clock Plus",
            tags = listOf("alarm clock plus", "alarm-clock-plus")
        ),
        IconEntry(
            key = "lucide:alarm-smoke",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_alarm_smoke,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Alarm Smoke",
            labelEn = "Alarm Smoke",
            tags = listOf("fire", "alarm-smoke", "carbon monoxide", "alarm smoke", "alert", "warning", "detector")
        ),
        IconEntry(
            key = "lucide:ambulance",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ambulance,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Ambulance",
            labelEn = "Ambulance",
            tags = listOf("ambulance", "emergency", "siren", "medical", "vehicle")
        ),
        IconEntry(
            key = "lucide:ampersand",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ampersand,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Ampersand",
            labelEn = "Ampersand",
            tags = listOf("operator", "ampersand", "and", "typography", "concatenate", "join")
        ),
        IconEntry(
            key = "lucide:ampersands",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ampersands,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Ampersands",
            labelEn = "Ampersands",
            tags = listOf("operator", "then", "and", "&&", "ampersands", "code")
        ),
        IconEntry(
            key = "lucide:amphora",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_amphora,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Amphora",
            labelEn = "Amphora",
            tags = listOf("amphora", "pottery", "artifact", "artefact", "vase", "ceramics")
        ),
        IconEntry(
            key = "lucide:angle",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_angle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Angle",
            labelEn = "Angle",
            tags = listOf("measure", "angle", "corner", "geometry", "degree")
        ),
        IconEntry(
            key = "lucide:antenna",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_antenna,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Antenna",
            labelEn = "Antenna",
            tags = listOf("connection", "television", "antenna", "tv", "signal", "connectivity")
        ),
        IconEntry(
            key = "lucide:anvil",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_anvil,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Anvil",
            labelEn = "Anvil",
            tags = listOf("alloy", "anvil", "heavy", "iron", "materials", "metal")
        ),
        IconEntry(
            key = "lucide:aperture",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_aperture,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Aperture",
            labelEn = "Aperture",
            tags = listOf("pictures", "shutter", "aperture", "exposure", "camera", "photo")
        ),
        IconEntry(
            key = "lucide:app-window-mac",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_app_window_mac,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "App Window Mac",
            labelEn = "App Window Mac",
            tags = listOf("app-window-mac", "macos", "app window mac", "title bar", "osx", "preferences", "desktop")
        ),
        IconEntry(
            key = "lucide:app-window",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_app_window,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "App Window",
            labelEn = "App Window",
            tags = listOf("menu bar", "pane", "app window", "executable", "application", "app-window")
        ),
        IconEntry(
            key = "lucide:archive-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_archive_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Archive Arrow Down",
            labelEn = "Archive Arrow Down",
            tags = listOf("archive-arrow-down", "save", "download", "unarchive", "email", "archive arrow down")
        ),
        IconEntry(
            key = "lucide:archive-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_archive_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Archive Arrow Up",
            labelEn = "Archive Arrow Up",
            tags = listOf("archive-arrow-up", "archive arrow up", "unarchive")
        ),
        IconEntry(
            key = "lucide:archive-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_archive_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Archive X",
            labelEn = "Archive X",
            tags = listOf("archive-x", "archive x", "junk")
        ),
        IconEntry(
            key = "lucide:archive",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_archive,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Archive",
            labelEn = "Archive",
            tags = listOf("backup", "archive", "records", "storage", "box", "index")
        ),
        IconEntry(
            key = "lucide:armenian-dram",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_armenian_dram,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Armenian Dram",
            labelEn = "Armenian Dram",
            tags = listOf("symbol", "armenian dram", "armenian-dram")
        ),
        IconEntry(
            key = "lucide:asterisk",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_asterisk,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Asterisk",
            labelEn = "Asterisk",
            tags = listOf("pencil", "asterisk", "symbol", "mark", "sign", "pointer")
        ),
        IconEntry(
            key = "lucide:astroid",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_astroid,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Astroid",
            labelEn = "Astroid",
            tags = listOf("sharp", "shape", "math", "star", "curve", "astroid")
        ),
        IconEntry(
            key = "lucide:at-sign",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_at_sign,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "At Sign",
            labelEn = "At Sign",
            tags = listOf("@", "at-sign", "message", "mention", "at sign", "email", "at")
        ),
        IconEntry(
            key = "lucide:audio-lines-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_audio_lines_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Audio Lines Off",
            labelEn = "Audio Lines Off",
            tags = listOf("equaliser", "silence", "mute", "audio-lines-off", "audio lines off", "audio", "equalizer")
        ),
        IconEntry(
            key = "lucide:audio-lines-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_audio_lines_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Audio Lines X",
            labelEn = "Audio Lines X",
            tags = listOf("silence", "mute", "unavailable", "audio lines x", "audio-lines-x", "equalizer", "disabled")
        ),
        IconEntry(
            key = "lucide:audio-lines",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_audio_lines,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Audio Lines",
            labelEn = "Audio Lines",
            tags = listOf("audio lines", "sound", "audio-lines", "hearing", "graphic equaliser", "noise", "listen")
        ),
        IconEntry(
            key = "lucide:audio-waveform",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_audio_waveform,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Audio Waveform",
            labelEn = "Audio Waveform",
            tags = listOf("sound", "hearing", "noise", "listen", "hertz", "audio waveform", "audio-waveform")
        ),
        IconEntry(
            key = "lucide:avocado",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_avocado,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Avocado",
            labelEn = "Avocado",
            tags = listOf("fruit", "ingredient", "lunch", "meal", "snack", "avocado")
        ),
        IconEntry(
            key = "lucide:axe",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_axe,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Axe",
            labelEn = "Axe",
            tags = listOf("hatchet", "chop", "equipment", "axe", "sharp", "weapon")
        ),
        IconEntry(
            key = "lucide:axis-3d",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_axis_3d,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Axis 3D",
            labelEn = "Axis 3D",
            tags = listOf("gizmo", "coordinates", "axis 3d", "model", "axis-3d")
        ),
        IconEntry(
            key = "lucide:azerbaijani-manat",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_azerbaijani_manat,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Azerbaijani Manat",
            labelEn = "Azerbaijani Manat",
            tags = listOf("azerbaijan", "azerbaijani manat", "azerbaijani-manat", "azn")
        ),
        IconEntry(
            key = "lucide:baby",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_baby,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Baby",
            labelEn = "Baby",
            tags = listOf("childproof", "children", "baby", "child")
        ),
        IconEntry(
            key = "lucide:backpack",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_backpack,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Backpack",
            labelEn = "Backpack",
            tags = listOf("travel", "hiking", "backpack", "bag", "school", "camping")
        ),
        IconEntry(
            key = "lucide:bacon",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bacon,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Bacon",
            labelEn = "Bacon",
            tags = listOf("rashers", "smoked", "bacon", "meat", "streaky", "crispy")
        ),
        IconEntry(
            key = "lucide:badge-alert",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_alert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Badge Alert",
            labelEn = "Badge Alert",
            tags = listOf("issue", "badge alert", "badge-alert", "security", "safety")
        ),
        IconEntry(
            key = "lucide:badge-cent",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_cent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Cent",
            labelEn = "Badge Cent",
            tags = listOf("tag", "badge cent", "offer", "sale", "discount", "badge-cent", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-dollar-sign",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_dollar_sign,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Dollar Sign",
            labelEn = "Badge Dollar Sign",
            tags = listOf("badge-dollar-sign", "badge dollar sign", "tag", "offer", "sale", "discount", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-euro",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_euro,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Euro",
            labelEn = "Badge Euro",
            tags = listOf("tag", "offer", "badge-euro", "sale", "badge euro", "discount", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-indian-rupee",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_indian_rupee,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Indian Rupee",
            labelEn = "Badge Indian Rupee",
            tags = listOf("tag", "offer", "sale", "voucher", "badge indian rupee", "discount", "badge-indian-rupee")
        ),
        IconEntry(
            key = "lucide:badge-japanese-yen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_japanese_yen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Japanese Yen",
            labelEn = "Badge Japanese Yen",
            tags = listOf("badge japanese yen", "tag", "offer", "badge-japanese-yen", "sale", "discount", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Badge Minus",
            labelEn = "Badge Minus",
            tags = listOf("badge-minus", "badge minus")
        ),
        IconEntry(
            key = "lucide:badge-percent",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_percent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Percent",
            labelEn = "Badge Percent",
            tags = listOf("badge percent", "badge-percent")
        ),
        IconEntry(
            key = "lucide:badge-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Badge Plus",
            labelEn = "Badge Plus",
            tags = listOf("badge plus", "badge-plus")
        ),
        IconEntry(
            key = "lucide:badge-pound-sterling",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_pound_sterling,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Pound Sterling",
            labelEn = "Badge Pound Sterling",
            tags = listOf("badge-pound-sterling", "tag", "offer", "badge pound sterling", "sale", "discount", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-question-mark",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_question_mark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Badge Question Mark",
            labelEn = "Badge Question Mark",
            tags = listOf("badge-question-mark", "badge question mark")
        ),
        IconEntry(
            key = "lucide:badge-russian-ruble",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_russian_ruble,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Russian Ruble",
            labelEn = "Badge Russian Ruble",
            tags = listOf("badge russian ruble", "tag", "offer", "sale", "badge-russian-ruble", "discount", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-swiss-franc",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_swiss_franc,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Swiss Franc",
            labelEn = "Badge Swiss Franc",
            tags = listOf("badge-swiss-franc", "tag", "offer", "badge swiss franc", "sale", "discount", "voucher")
        ),
        IconEntry(
            key = "lucide:badge-turkish-lira",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_turkish_lira,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Badge Turkish Lira",
            labelEn = "Badge Turkish Lira",
            tags = listOf("tag", "badge-turkish-lira", "offer", "sale", "discount", "voucher", "badge turkish lira")
        ),
        IconEntry(
            key = "lucide:badge-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_badge_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Badge X",
            labelEn = "Badge X",
            tags = listOf("lost", "badge x", "badge-x")
        ),
        IconEntry(
            key = "lucide:baggage-claim",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_baggage_claim,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Baggage Claim",
            labelEn = "Baggage Claim",
            tags = listOf("baggage", "travel", "trolley", "baggage claim", "baggage-claim", "cart", "luggage")
        ),
        IconEntry(
            key = "lucide:ball-wave",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ball_wave,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Ball Wave",
            labelEn = "Ball Wave",
            tags = listOf("athlete", "watercraft", "polo", "ball wave", "sport", "ball-wave", "feature")
        ),
        IconEntry(
            key = "lucide:balloon",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_balloon,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Balloon",
            labelEn = "Balloon",
            tags = listOf("celebration", "festival", "party", "congratulations", "decoration", "balloon")
        ),
        IconEntry(
            key = "lucide:ban",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ban,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Ban",
            labelEn = "Ban",
            tags = listOf("prohibited", "no", "stop", "ban", "forbidden", "cancel")
        ),
        IconEntry(
            key = "lucide:banana",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_banana,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Banana",
            labelEn = "Banana",
            tags = listOf("food", "fruit", "banana")
        ),
        IconEntry(
            key = "lucide:bangladeshi-taka",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bangladeshi_taka,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Bangladeshi Taka",
            labelEn = "Bangladeshi Taka",
            tags = listOf("৳", "bdt", "remittance", "bangladeshi taka", "ecommerce", "bangladeshi-taka")
        ),
        IconEntry(
            key = "lucide:banknote-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_banknote_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Banknote Arrow Down",
            labelEn = "Banknote Arrow Down",
            tags = listOf("banknote arrow down", "banknote-arrow-down", "withdraw", "refund", "expense", "out", "payout")
        ),
        IconEntry(
            key = "lucide:banknote-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_banknote_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Banknote Arrow Up",
            labelEn = "Banknote Arrow Up",
            tags = listOf("banknote-arrow-up", "deposit", "in", "credit", "banknote arrow up", "income", "earnings")
        ),
        IconEntry(
            key = "lucide:banknote-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_banknote_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Banknote Check",
            labelEn = "Banknote Check",
            tags = listOf("banknote", "banknote check", "document", "found", "paid", "verification", "banknote-check")
        ),
        IconEntry(
            key = "lucide:banknote-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_banknote_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Banknote X",
            labelEn = "Banknote X",
            tags = listOf("rejected", "failed", "declined", "error", "banknote x", "lost", "banknote-x")
        ),
        IconEntry(
            key = "lucide:barcode",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_barcode,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Barcode",
            labelEn = "Barcode",
            tags = listOf("till", "barcode", "transaction", "checkout", "cart", "scan")
        ),
        IconEntry(
            key = "lucide:barrel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_barrel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Barrel",
            labelEn = "Barrel",
            tags = listOf("beer", "barrel", "wine", "tank", "keg", "drum")
        ),
        IconEntry(
            key = "lucide:baseline",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_baseline,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Baseline",
            labelEn = "Baseline",
            tags = listOf("format", "color", "baseline")
        ),
        IconEntry(
            key = "lucide:battery-charging",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery_charging,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery Charging",
            labelEn = "Battery Charging",
            tags = listOf("battery-charging", "battery charging")
        ),
        IconEntry(
            key = "lucide:battery-full",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery_full,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery Full",
            labelEn = "Battery Full",
            tags = listOf("battery-full", "battery full")
        ),
        IconEntry(
            key = "lucide:battery-low",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery_low,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery Low",
            labelEn = "Battery Low",
            tags = listOf("battery-low", "battery low")
        ),
        IconEntry(
            key = "lucide:battery-medium",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery_medium,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery Medium",
            labelEn = "Battery Medium",
            tags = listOf("battery-medium", "battery medium")
        ),
        IconEntry(
            key = "lucide:battery-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery Plus",
            labelEn = "Battery Plus",
            tags = listOf("upgrade", "battery plus", "health", "maximum", "economy", "battery-plus")
        ),
        IconEntry(
            key = "lucide:battery-warning",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery_warning,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery Warning",
            labelEn = "Battery Warning",
            tags = listOf("battery warning", "battery-warning")
        ),
        IconEntry(
            key = "lucide:battery",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_battery,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Battery",
            labelEn = "Battery",
            tags = listOf("power", "charge", "electricity", "battery", "accumulator", "energy")
        ),
        IconEntry(
            key = "lucide:beaker",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_beaker,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Beaker",
            labelEn = "Beaker",
            tags = listOf("test", "beaker", "chemistry", "experiment", "cup", "lab")
        ),
        IconEntry(
            key = "lucide:bean-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bean_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bean Off",
            labelEn = "Bean Off",
            tags = listOf("bean-off", "bean off", "intolerance", "diet", "soy free", "allergy")
        ),
        IconEntry(
            key = "lucide:bean",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bean,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Bean",
            labelEn = "Bean",
            tags = listOf("bean", "legume", "food", "soy", "seed")
        ),
        IconEntry(
            key = "lucide:bed-single",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bed_single,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bed Single",
            labelEn = "Bed Single",
            tags = listOf("bed single", "bed-single")
        ),
        IconEntry(
            key = "lucide:beef-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_beef_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Beef Off",
            labelEn = "Beef Off",
            tags = listOf("beef off", "beef-off", "vegetarian")
        ),
        IconEntry(
            key = "lucide:beef",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_beef,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Beef",
            labelEn = "Beef",
            tags = listOf("restaurant", "food", "beef", "meal", "course", "dish")
        ),
        IconEntry(
            key = "lucide:beer-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_beer_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Beer Off",
            labelEn = "Beer Off",
            tags = listOf("beer-off", "beer off")
        ),
        IconEntry(
            key = "lucide:belarusian-ruble",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_belarusian_ruble,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Belarusian Ruble",
            labelEn = "Belarusian Ruble",
            tags = listOf("belarusian-ruble", "localization", "fiat", "byn", "belarusian ruble", "iso4217", "checkout")
        ),
        IconEntry(
            key = "lucide:bell-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Check",
            labelEn = "Bell Check",
            tags = listOf("bell-check", "bell check")
        ),
        IconEntry(
            key = "lucide:bell-dot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_dot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Dot",
            labelEn = "Bell Dot",
            tags = listOf("bell-dot", "bell dot")
        ),
        IconEntry(
            key = "lucide:bell-electric",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_electric,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Electric",
            labelEn = "Bell Electric",
            tags = listOf("flames", "bell electric", "smoke", "fire alarm", "bell-electric", "fireman", "firefighter")
        ),
        IconEntry(
            key = "lucide:bell-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Minus",
            labelEn = "Bell Minus",
            tags = listOf("silent", "bell minus", "bell-minus")
        ),
        IconEntry(
            key = "lucide:bell-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Off",
            labelEn = "Bell Off",
            tags = listOf("bell off", "silent", "bell-off")
        ),
        IconEntry(
            key = "lucide:bell-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Plus",
            labelEn = "Bell Plus",
            tags = listOf("bell-plus", "bell plus", "silent")
        ),
        IconEntry(
            key = "lucide:bell-ring",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bell_ring,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bell Ring",
            labelEn = "Bell Ring",
            tags = listOf("bell-ring", "bell ring")
        ),
        IconEntry(
            key = "lucide:biceps-flexed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_biceps_flexed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Biceps Flexed",
            labelEn = "Biceps Flexed",
            tags = listOf("muscle", "biceps flexed", "working out", "strong", "biceps-flexed", "athletic", "arm")
        ),
        IconEntry(
            key = "lucide:binoculars",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_binoculars,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Binoculars",
            labelEn = "Binoculars",
            tags = listOf("lorgnette", "pince-nez", "field glasses", "sightseeing", "binoculars", "observation")
        ),
        IconEntry(
            key = "lucide:biohazard",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_biohazard,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Biohazard",
            labelEn = "Biohazard",
            tags = listOf("chemistry", "chemical", "biohazard", "biology", "waste", "fallout")
        ),
        IconEntry(
            key = "lucide:bird",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bird,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Bird",
            labelEn = "Bird",
            tags = listOf("bird", "tweet", "avian", "freedom", "wing", "peace")
        ),
        IconEntry(
            key = "lucide:birdhouse",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_birdhouse,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Birdhouse",
            labelEn = "Birdhouse",
            tags = listOf("bird", "house", "birdhouse", "garden", "home")
        ),
        IconEntry(
            key = "lucide:bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bitcoin,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Bitcoin",
            labelEn = "Bitcoin",
            tags = listOf("market", "coin", "digital", "cryptocurrency", "bitcoin", "blockchain")
        ),
        IconEntry(
            key = "lucide:blend",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_blend,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Blend",
            labelEn = "Blend",
            tags = listOf("blend", "opacity", "multiply", "overlay", "mode", "screen")
        ),
        IconEntry(
            key = "lucide:blender",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_blender,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Blender",
            labelEn = "Blender",
            tags = listOf("food", "mixer", "juicer", "blender", "appliances", "liquid")
        ),
        IconEntry(
            key = "lucide:blinds",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_blinds,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Blinds",
            labelEn = "Blinds",
            tags = listOf("shutter", "roller blind", "blinds", "curtain", "shades", "screen")
        ),
        IconEntry(
            key = "lucide:bluetooth-connected",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bluetooth_connected,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bluetooth Connected",
            labelEn = "Bluetooth Connected",
            tags = listOf("bluetooth connected", "paired", "bluetooth-connected")
        ),
        IconEntry(
            key = "lucide:bluetooth-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bluetooth_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bluetooth Off",
            labelEn = "Bluetooth Off",
            tags = listOf("bluetooth off", "lost", "bluetooth-off")
        ),
        IconEntry(
            key = "lucide:bluetooth-searching",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bluetooth_searching,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bluetooth Searching",
            labelEn = "Bluetooth Searching",
            tags = listOf("bluetooth searching", "bluetooth-searching", "pairing")
        ),
        IconEntry(
            key = "lucide:bluetooth",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bluetooth,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bluetooth",
            labelEn = "Bluetooth",
            tags = listOf("bluetooth", "wireless")
        ),
        IconEntry(
            key = "lucide:bolt",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bolt,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Bolt",
            labelEn = "Bolt",
            tags = listOf("settings", "screw", "nut", "bolt", "configuration", "preferences")
        ),
        IconEntry(
            key = "lucide:bomb",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bomb,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Bomb",
            labelEn = "Bomb",
            tags = listOf("bomb", "mine", "fatal", "blockbuster", "error", "crash")
        ),
    )

    private fun getPart4(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:bone-fracture",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bone_fracture,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Bone Fracture",
            labelEn = "Bone Fracture",
            tags = listOf("fracture", "orthopedic", "medical", "bone", "injury", "bone-fracture", "bone fracture")
        ),
        IconEntry(
            key = "lucide:bone",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Bone",
            labelEn = "Bone",
            tags = listOf("death", "bone", "skeleton", "health", "skull", "pets")
        ),
        IconEntry(
            key = "lucide:book-a",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_a,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Book A",
            labelEn = "Book A",
            tags = listOf("dictionary", "encyclopedia", "book a", "definition", "book-a", "thesaurus", "define")
        ),
        IconEntry(
            key = "lucide:book-alert",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_alert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Alert",
            labelEn = "Book Alert",
            tags = listOf("book alert", "book-alert")
        ),
        IconEntry(
            key = "lucide:book-audio",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_audio,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Book Audio",
            labelEn = "Book Audio",
            tags = listOf("book audio", "book-audio", "listening", "audiobook")
        ),
        IconEntry(
            key = "lucide:book-bookmark",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_bookmark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Bookmark",
            labelEn = "Book Bookmark",
            tags = listOf("saved", "dictionary", "book-bookmark", "later", "future", "book bookmark", "reference")
        ),
        IconEntry(
            key = "lucide:book-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Check",
            labelEn = "Book Check",
            tags = listOf("book check", "read", "authored", "published", "knowledgeable", "book-check", "informed")
        ),
        IconEntry(
            key = "lucide:book-copy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_copy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Copy",
            labelEn = "Book Copy",
            tags = listOf("book copy", "version control", "repository", "git", "book-copy", "coding", "code")
        ),
        IconEntry(
            key = "lucide:book-dashed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_dashed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Book Dashed",
            labelEn = "Book Dashed",
            tags = listOf("version control", "repository", "git", "book dashed", "book-dashed", "coding", "code")
        ),
        IconEntry(
            key = "lucide:book-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Book Down",
            labelEn = "Book Down",
            tags = listOf("version control", "book down", "repository", "git", "coding", "code", "book-down")
        ),
        IconEntry(
            key = "lucide:book-headphones",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_headphones,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Book Headphones",
            labelEn = "Book Headphones",
            tags = listOf("listening", "book-headphones", "book headphones", "audiobook")
        ),
        IconEntry(
            key = "lucide:book-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Book Heart",
            labelEn = "Book Heart",
            tags = listOf("diary", "romance", "entries", "book-heart", "journal", "entry", "book heart")
        ),
        IconEntry(
            key = "lucide:book-image",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_image,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Book Image",
            labelEn = "Book Image",
            tags = listOf("pictures", "book image", "collection", "images", "book-image", "album", "photos")
        ),
        IconEntry(
            key = "lucide:book-key",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_key,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Book Key",
            labelEn = "Book Key",
            tags = listOf("version control", "book key", "repository", "git", "book-key", "coding", "code")
        ),
        IconEntry(
            key = "lucide:book-lock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_lock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Book Lock",
            labelEn = "Book Lock",
            tags = listOf("version control", "repository", "git", "book-lock", "coding", "book lock", "code")
        ),
        IconEntry(
            key = "lucide:book-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Minus",
            labelEn = "Book Minus",
            tags = listOf("book-minus", "version control", "repository", "book minus", "git", "coding", "code")
        ),
        IconEntry(
            key = "lucide:book-open-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_open_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Open Check",
            labelEn = "Book Open Check",
            tags = listOf("read", "authored", "published", "knowledgeable", "book open check", "book-open-check", "informed")
        ),
        IconEntry(
            key = "lucide:book-open-text",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_open_text,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Book Open Text",
            labelEn = "Book Open Text",
            tags = listOf("book open text", "book-open-text")
        ),
        IconEntry(
            key = "lucide:book-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Plus",
            labelEn = "Book Plus",
            tags = listOf("book plus", "version control", "book-plus", "repository", "git", "coding", "code")
        ),
        IconEntry(
            key = "lucide:book-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_search,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Book Search",
            labelEn = "Book Search",
            tags = listOf("book-search", "book search")
        ),
        IconEntry(
            key = "lucide:book-text",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_text,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Book Text",
            labelEn = "Book Text",
            tags = listOf("book-text", "book text")
        ),
        IconEntry(
            key = "lucide:book-type",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_type,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Book Type",
            labelEn = "Book Type",
            tags = listOf("fonts", "synonym", "book type", "book-type", "thesaurus", "translate", "language")
        ),
        IconEntry(
            key = "lucide:book-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Book Up",
            labelEn = "Book Up",
            tags = listOf("book-up", "version control", "repository", "git", "coding", "book up", "code")
        ),
        IconEntry(
            key = "lucide:book-user",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_user,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Book User",
            labelEn = "Book User",
            tags = listOf("acquaintances", "addresses", "friends", "book-user", "family", "book user", "details")
        ),
        IconEntry(
            key = "lucide:book-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_book_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Book X",
            labelEn = "Book X",
            tags = listOf("version control", "repository", "git", "coding", "book x", "code", "book-x")
        ),
        IconEntry(
            key = "lucide:bookmark-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bookmark_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bookmark Check",
            labelEn = "Bookmark Check",
            tags = listOf("bookmark check", "bookmark-check")
        ),
        IconEntry(
            key = "lucide:bookmark-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bookmark_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bookmark Minus",
            labelEn = "Bookmark Minus",
            tags = listOf("bookmark minus", "bookmark-minus")
        ),
        IconEntry(
            key = "lucide:bookmark-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bookmark_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bookmark Off",
            labelEn = "Bookmark Off",
            tags = listOf("unlabel", "unfavorite", "bookmark off", "removed", "unsaved", "bookmark-off", "unmarked")
        ),
        IconEntry(
            key = "lucide:bookmark-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bookmark_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bookmark Plus",
            labelEn = "Bookmark Plus",
            tags = listOf("bookmark plus", "bookmark-plus")
        ),
        IconEntry(
            key = "lucide:bookmark-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bookmark_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bookmark X",
            labelEn = "Bookmark X",
            tags = listOf("bookmark-x", "bookmark x")
        ),
        IconEntry(
            key = "lucide:boom-box",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_boom_box,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Boom Box",
            labelEn = "Boom Box",
            tags = listOf("sound", "music", "speakers", "boom-box", "boom box", "audio", "radio")
        ),
        IconEntry(
            key = "lucide:bot-message-square",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bot_message_square,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bot Message Square",
            labelEn = "Bot Message Square",
            tags = listOf("bot message square", "bot-message-square")
        ),
        IconEntry(
            key = "lucide:bot-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bot_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bot Off",
            labelEn = "Bot Off",
            tags = listOf("bot off", "bot-off")
        ),
        IconEntry(
            key = "lucide:bot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Bot",
            labelEn = "Bot",
            tags = listOf("chat", "ai", "assistant", "bot", "robot")
        ),
        IconEntry(
            key = "lucide:bottle-wine",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bottle_wine,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Bottle Wine",
            labelEn = "Bottle Wine",
            tags = listOf("alcohol", "glass", "drink", "chalice", "goblet", "bottle-wine", "bottle wine")
        ),
        IconEntry(
            key = "lucide:bow-arrow",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bow_arrow,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Bow Arrow",
            labelEn = "Bow Arrow",
            tags = listOf("archery", "bow-arrow", "bow arrow", "archer", "war", "game", "weapon")
        ),
        IconEntry(
            key = "lucide:box",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_box,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Box",
            labelEn = "Box",
            tags = listOf("container", "storage", "box", "cube", "geometry", "package")
        ),
        IconEntry(
            key = "lucide:boxes-linked",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_boxes_linked,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Boxes Linked",
            labelEn = "Boxes Linked",
            tags = listOf("cubes", "dependencies", "boxes-linked", "boxes linked", "module", "package", "link")
        ),
        IconEntry(
            key = "lucide:boxes",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_boxes,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Boxes",
            labelEn = "Boxes",
            tags = listOf("cubes", "group", "packages", "units", "parts", "boxes")
        ),
        IconEntry(
            key = "lucide:braces",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_braces,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Braces",
            labelEn = "Braces",
            tags = listOf("braces", "curly brackets", "json", "curly braces", "token", "code")
        ),
        IconEntry(
            key = "lucide:brackets",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brackets,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Brackets",
            labelEn = "Brackets",
            tags = listOf("list", "array", "square", "brackets", "token", "code")
        ),
        IconEntry(
            key = "lucide:brain-circuit",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brain_circuit,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Brain Circuit",
            labelEn = "Brain Circuit",
            tags = listOf("brain circuit", "brain-circuit")
        ),
        IconEntry(
            key = "lucide:brain-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brain_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Brain Cog",
            labelEn = "Brain Cog",
            tags = listOf("brain-cog", "brain cog")
        ),
        IconEntry(
            key = "lucide:brain-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brain_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Brain Off",
            labelEn = "Brain Off",
            tags = listOf("brain off", "brain-off")
        ),
        IconEntry(
            key = "lucide:brick-wall-fire",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brick_wall_fire,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Brick Wall Fire",
            labelEn = "Brick Wall Fire",
            tags = listOf("campfire", "wilderness", "brick wall fire", "firewall", "security", "camping", "brick-wall-fire")
        ),
        IconEntry(
            key = "lucide:brick-wall-shield",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brick_wall_shield,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Brick Wall Shield",
            labelEn = "Brick Wall Shield",
            tags = listOf("armoured", "brick wall shield", "brick-wall-shield", "firewall", "armored", "block", "threat")
        ),
        IconEntry(
            key = "lucide:brick-wall",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brick_wall,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Brick Wall",
            labelEn = "Brick Wall",
            tags = listOf("bricks", "cement", "brick-wall", "mortar", "construction", "materials", "brick wall")
        ),
        IconEntry(
            key = "lucide:bridge",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bridge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Bridge",
            labelEn = "Bridge",
            tags = listOf("viaduct", "traverse", "cross over", "bridge", "suspension", "overpass")
        ),
        IconEntry(
            key = "lucide:briefcase-business",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_briefcase_business,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Briefcase Business",
            labelEn = "Briefcase Business",
            tags = listOf("briefcase business", "briefcase-business", "portfolio")
        ),
        IconEntry(
            key = "lucide:briefcase-conveyor-belt",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_briefcase_conveyor_belt,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Briefcase Conveyor Belt",
            labelEn = "Briefcase Conveyor Belt",
            tags = listOf("baggage", "briefcase conveyor belt", "briefcase-conveyor-belt", "travel", "suitcase", "luggage", "conveyor")
        ),
        IconEntry(
            key = "lucide:briefcase-medical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_briefcase_medical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Аптечка",
            labelEn = "Briefcase Medical",
            tags = listOf("briefcase-medical", "briefcase medical", "first aid", "medicine", "doctor", "здоровье", "аптечка", "первая помощь")
        ),
        IconEntry(
            key = "lucide:briefcase-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_briefcase_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Briefcase Plus",
            labelEn = "Briefcase Plus",
            tags = listOf("briefcase", "business", "career", "portfolio", "briefcase-plus", "briefcase plus", "employment")
        ),
        IconEntry(
            key = "lucide:bring-to-front",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bring_to_front,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Bring To Front",
            labelEn = "Bring To Front",
            tags = listOf("move", "forward", "bring to front", "send", "over", "bring-to-front", "bring")
        ),
        IconEntry(
            key = "lucide:broccoli",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_broccoli,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Broccoli",
            labelEn = "Broccoli",
            tags = listOf("culinary", "fresh", "crisp", "leafy", "vegetation", "broccoli")
        ),
        IconEntry(
            key = "lucide:broom-sparkles",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_broom_sparkles,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Broom Sparkles",
            labelEn = "Broom Sparkles",
            tags = listOf("pristine", "broom-sparkles", "broom sparkles")
        ),
        IconEntry(
            key = "lucide:broom",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_broom,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Broom",
            labelEn = "Broom",
            tags = listOf("sweep", "sweeping", "brush", "broomstick", "floor", "broom")
        ),
        IconEntry(
            key = "lucide:brush-cleaning",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_brush_cleaning,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Brush Cleaning",
            labelEn = "Brush Cleaning",
            tags = listOf("brush-cleaning", "tool", "sweeping", "utensil", "housekeeping", "brush cleaning", "cleaning")
        ),
        IconEntry(
            key = "lucide:bubbles",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bubbles,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Bubbles",
            labelEn = "Bubbles",
            tags = listOf("water", "soap", "cleaning", "hygiene", "bubbles", "bath")
        ),
        IconEntry(
            key = "lucide:bug-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bug_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bug Off",
            labelEn = "Bug Off",
            tags = listOf("pest control", "bug-off", "resolved", "kill", "exterminate", "bug off", "fixed")
        ),
        IconEntry(
            key = "lucide:bug-play",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bug_play,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bug Play",
            labelEn = "Bug Play",
            tags = listOf("bug-play", "bug play", "reproduce")
        ),
        IconEntry(
            key = "lucide:building-complex-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_building_complex_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Building Complex Plus",
            labelEn = "Building Complex Plus",
            tags = listOf("building complex plus", "registration", "startup", "headquarters", "building-complex-plus", "onboarding", "office")
        ),
        IconEntry(
            key = "lucide:building-complex",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_building_complex,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Building Complex",
            labelEn = "Building Complex",
            tags = listOf("building-complex", "skyscraper", "enterprise", "business", "company", "city", "building complex")
        ),
        IconEntry(
            key = "lucide:bus-front",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bus_front,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Bus Front",
            labelEn = "Bus Front",
            tags = listOf("bus front", "bus-front")
        ),
        IconEntry(
            key = "lucide:bus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_bus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Bus",
            labelEn = "Bus",
            tags = listOf("trip", "bus", "coach", "vehicle", "transport")
        ),
        IconEntry(
            key = "lucide:cable-car",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cable_car,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Cable Car",
            labelEn = "Cable Car",
            tags = listOf("cable car", "mountains", "winter holiday", "ski lift", "resort", "alpine", "cable-car")
        ),
        IconEntry(
            key = "lucide:cake-slice",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cake_slice,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Cake Slice",
            labelEn = "Cake Slice",
            tags = listOf("cake slice", "cake-slice")
        ),
        IconEntry(
            key = "lucide:calendar-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Arrow Down",
            labelEn = "Calendar Arrow Down",
            tags = listOf("calendar arrow down", "calendar-arrow-down")
        ),
        IconEntry(
            key = "lucide:calendar-arrow-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_arrow_left,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Arrow Left",
            labelEn = "Calendar Arrow Left",
            tags = listOf("past", "arrow", "calendar-arrow-left", "reschedule", "calendar arrow left", "previous", "earlier")
        ),
        IconEntry(
            key = "lucide:calendar-arrow-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_arrow_right,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Arrow Right",
            labelEn = "Calendar Arrow Right",
            tags = listOf("calendar-arrow-right", "upcoming", "arrow", "next", "future", "later", "calendar arrow right")
        ),
        IconEntry(
            key = "lucide:calendar-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Arrow Up",
            labelEn = "Calendar Arrow Up",
            tags = listOf("calendar-arrow-up", "calendar arrow up")
        ),
        IconEntry(
            key = "lucide:calendar-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Check",
            labelEn = "Calendar Check",
            tags = listOf("subscribe", "calendar-check", "schedule", "calendar check")
        ),
        IconEntry(
            key = "lucide:calendar-chevrons-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_chevrons_left,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Chevrons Left",
            labelEn = "Calendar Chevrons Left",
            tags = listOf("indicator", "arrow", "symbol", "calendar-chevrons-left", "menu", "calendar chevrons left", "pointer")
        ),
        IconEntry(
            key = "lucide:calendar-chevrons-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_chevrons_right,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Chevrons Right",
            labelEn = "Calendar Chevrons Right",
            tags = listOf("calendar chevrons right", "indicator", "arrow", "symbol", "calendar-chevrons-right", "menu", "pointer")
        ),
        IconEntry(
            key = "lucide:calendar-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Cog",
            labelEn = "Calendar Cog",
            tags = listOf("events", "calendar-cog", "calendar cog")
        ),
        IconEntry(
            key = "lucide:calendar-fold",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_fold,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Fold",
            labelEn = "Calendar Fold",
            tags = listOf("calendar-fold", "ics", "calendar fold")
        ),
        IconEntry(
            key = "lucide:calendar-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Heart",
            labelEn = "Calendar Heart",
            tags = listOf("subscribe", "valentines day", "calendar-heart", "calendar heart")
        ),
        IconEntry(
            key = "lucide:calendar-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Minus",
            labelEn = "Calendar Minus",
            tags = listOf("calendar minus", "calendar-minus")
        ),
        IconEntry(
            key = "lucide:calendar-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Off",
            labelEn = "Calendar Off",
            tags = listOf("calendar-off", "calendar off")
        ),
        IconEntry(
            key = "lucide:calendar-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Plus",
            labelEn = "Calendar Plus",
            tags = listOf("subscribe", "calendar-plus", "calendar plus")
        ),
        IconEntry(
            key = "lucide:calendar-range",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_range,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Range",
            labelEn = "Calendar Range",
            tags = listOf("range", "calendar-range", "period", "calendar range")
        ),
        IconEntry(
            key = "lucide:calendar-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_search,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar Search",
            labelEn = "Calendar Search",
            tags = listOf("calendar-search", "events", "calendar search")
        ),
        IconEntry(
            key = "lucide:calendar-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendar_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendar X",
            labelEn = "Calendar X",
            tags = listOf("calendar x", "busy", "calendar-x")
        ),
        IconEntry(
            key = "lucide:calendars",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_calendars,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Calendars",
            labelEn = "Calendars",
            tags = listOf("month", "year", "dates", "date", "event", "calendars")
        ),
        IconEntry(
            key = "lucide:camera-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_camera_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Camera Off",
            labelEn = "Camera Off",
            tags = listOf("camera-off", "camera off")
        ),
        IconEntry(
            key = "lucide:can-soda",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_can_soda,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Can Soda",
            labelEn = "Can Soda",
            tags = listOf("can soda", "can-soda", "food", "drink", "beverage", "tin", "soda")
        ),
        IconEntry(
            key = "lucide:can",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_can,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Can",
            labelEn = "Can",
            tags = listOf("can", "groceries", "food", "storage", "tin", "metal")
        ),
        IconEntry(
            key = "lucide:candy-cane",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_candy_cane,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Candy Cane",
            labelEn = "Candy Cane",
            tags = listOf("xmas", "candy cane", "candy-cane", "christmas")
        ),
        IconEntry(
            key = "lucide:candy-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_candy_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Candy Off",
            labelEn = "Candy Off",
            tags = listOf("intolerance", "candy off", "diet", "candy-off", "sugar free", "allergy")
        ),
        IconEntry(
            key = "lucide:candy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_candy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Candy",
            labelEn = "Candy",
            tags = listOf("food", "sugar", "candy", "sweet")
        ),
        IconEntry(
            key = "lucide:cannabis-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cannabis_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cannabis Off",
            labelEn = "Cannabis Off",
            tags = listOf("cannabis-off", "cannabis off")
        ),
        IconEntry(
            key = "lucide:cannabis",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cannabis,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Cannabis",
            labelEn = "Cannabis",
            tags = listOf("leaf", "weed", "cannabis")
        ),
        IconEntry(
            key = "lucide:captions-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_captions_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Captions Off",
            labelEn = "Captions Off",
            tags = listOf("captions-off", "captions off")
        ),
        IconEntry(
            key = "lucide:captions",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_captions,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Captions",
            labelEn = "Captions",
            tags = listOf("closed captions", "transcription", "subhead", "subtitles", "transcribe", "captions")
        ),
        IconEntry(
            key = "lucide:car-battery",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_car_battery,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Car Battery",
            labelEn = "Car Battery",
            tags = listOf("power cell", "power", "car battery", "battery", "electric", "car-battery", "automobile")
        ),
        IconEntry(
            key = "lucide:car-front",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_car_front,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Car Front",
            labelEn = "Car Front",
            tags = listOf("car-front", "car front")
        ),
        IconEntry(
            key = "lucide:car-taxi-front",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_car_taxi_front,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Car Taxi Front",
            labelEn = "Car Taxi Front",
            tags = listOf("car taxi front", "cab", "car-taxi-front")
        ),
        IconEntry(
            key = "lucide:caravan",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_caravan,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Caravan",
            labelEn = "Caravan",
            tags = listOf("caravan", "trailer", "camping", "tow", "campsite", "mobile home")
        ),
        IconEntry(
            key = "lucide:card-sim",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_card_sim,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Card Sim",
            labelEn = "Card Sim",
            tags = listOf("smartphone", "cellphone", "network", "cellular", "mobile", "card-sim", "card sim")
        ),
        IconEntry(
            key = "lucide:carrot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_carrot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Carrot",
            labelEn = "Carrot",
            tags = listOf("healthy", "orange", "carrot", "fresh", "nature", "root")
        ),
        IconEntry(
            key = "lucide:carton-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_carton_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Carton Off",
            labelEn = "Carton Off",
            tags = listOf("non dairy", "vegan", "intolerance", "lactose free", "carton off", "allergy", "carton-off")
        ),
    )

    private fun getPart5(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:carton",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_carton,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Carton",
            labelEn = "Carton",
            tags = listOf("drink", "dairy", "diet", "carton", "beverage", "milk")
        ),
        IconEntry(
            key = "lucide:case-lower",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_case_lower,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Case Lower",
            labelEn = "Case Lower",
            tags = listOf("characters", "case-lower", "case lower", "letters")
        ),
        IconEntry(
            key = "lucide:case-sensitive",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_case_sensitive,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Case Sensitive",
            labelEn = "Case Sensitive",
            tags = listOf("case-sensitive", "letters", "characters", "case sensitive")
        ),
        IconEntry(
            key = "lucide:case-upper",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_case_upper,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Case Upper",
            labelEn = "Case Upper",
            tags = listOf("case upper", "case-upper", "letters", "characters")
        ),
        IconEntry(
            key = "lucide:cassette-tape",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cassette_tape,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Cassette Tape",
            labelEn = "Cassette Tape",
            tags = listOf("music", "recording", "cassette tape", "cassette-tape", "audio", "play")
        ),
        IconEntry(
            key = "lucide:cast",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cast,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cast",
            labelEn = "Cast",
            tags = listOf("display", "stream", "wireless", "streaming", "cast", "screen")
        ),
        IconEntry(
            key = "lucide:castle",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_castle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Castle",
            labelEn = "Castle",
            tags = listOf("castle", "fortress", "chateau", "building", "stronghold", "palace")
        ),
        IconEntry(
            key = "lucide:cctv-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cctv_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cctv Off",
            labelEn = "Cctv Off",
            tags = listOf("cctv-off", "cctv off")
        ),
        IconEntry(
            key = "lucide:cctv",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cctv,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Cctv",
            labelEn = "Cctv",
            tags = listOf("cctv", "recording", "videotape", "camera", "film", "surveillance")
        ),
        IconEntry(
            key = "lucide:certificate",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_certificate,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Certificate",
            labelEn = "Certificate",
            tags = listOf("achievement", "award", "medal", "certificate", "ribbon", "pinned")
        ),
        IconEntry(
            key = "lucide:chart-area",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_area,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Area",
            labelEn = "Chart Area",
            tags = listOf("chart area", "chart-area")
        ),
        IconEntry(
            key = "lucide:chart-bar-big",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_bar_big,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Bar Big",
            labelEn = "Chart Bar Big",
            tags = listOf("chart bar big", "chart-bar-big")
        ),
        IconEntry(
            key = "lucide:chart-bar-decreasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_bar_decreasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Bar Decreasing",
            labelEn = "Chart Bar Decreasing",
            tags = listOf("chart-bar-decreasing", "trending down", "chart bar decreasing")
        ),
        IconEntry(
            key = "lucide:chart-bar-increasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_bar_increasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Bar Increasing",
            labelEn = "Chart Bar Increasing",
            tags = listOf("trending up", "chart bar increasing", "chart-bar-increasing")
        ),
        IconEntry(
            key = "lucide:chart-bar-stacked",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_bar_stacked,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Bar Stacked",
            labelEn = "Chart Bar Stacked",
            tags = listOf("chart-bar-stacked", "chart bar stacked", "multivariate")
        ),
        IconEntry(
            key = "lucide:chart-bar",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_bar,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Bar",
            labelEn = "Chart Bar",
            tags = listOf("chart-bar", "chart bar", "categorical", "comparison")
        ),
        IconEntry(
            key = "lucide:chart-bell-curve",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_bell_curve,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Bell Curve",
            labelEn = "Chart Bell Curve",
            tags = listOf("chart bell curve", "frequency", "probability", "gaussian", "distribution", "normal distribution", "chart-bell-curve")
        ),
        IconEntry(
            key = "lucide:chart-candlestick",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_candlestick,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Chart Candlestick",
            labelEn = "Chart Candlestick",
            tags = listOf("markets", "chart-candlestick", "trading", "financial", "chart candlestick", "portfolio", "trader")
        ),
        IconEntry(
            key = "lucide:chart-column-big",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_column_big,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Column Big",
            labelEn = "Chart Column Big",
            tags = listOf("chart column big", "chart-column-big")
        ),
        IconEntry(
            key = "lucide:chart-column-decreasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_column_decreasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Column Decreasing",
            labelEn = "Chart Column Decreasing",
            tags = listOf("chart column decreasing", "chart-column-decreasing", "trending down")
        ),
        IconEntry(
            key = "lucide:chart-column-increasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_column_increasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Column Increasing",
            labelEn = "Chart Column Increasing",
            tags = listOf("trending up", "chart column increasing", "chart-column-increasing")
        ),
        IconEntry(
            key = "lucide:chart-column-stacked",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_column_stacked,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Column Stacked",
            labelEn = "Chart Column Stacked",
            tags = listOf("chart-column-stacked", "multivariate", "chart column stacked")
        ),
        IconEntry(
            key = "lucide:chart-column",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_column,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Column",
            labelEn = "Chart Column",
            tags = listOf("comparison", "categorical", "chart-column", "chart column")
        ),
        IconEntry(
            key = "lucide:chart-gantt",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_gantt,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Gantt",
            labelEn = "Chart Gantt",
            tags = listOf("manage", "timeline", "planning", "overview", "chart gantt", "chart-gantt", "projects")
        ),
        IconEntry(
            key = "lucide:chart-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_line,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Line",
            labelEn = "Chart Line",
            tags = listOf("chart line", "chart-line")
        ),
        IconEntry(
            key = "lucide:chart-network",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_network,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Network",
            labelEn = "Chart Network",
            tags = listOf("cluster", "chart-network", "chart network", "web")
        ),
        IconEntry(
            key = "lucide:chart-no-axes-column-decreasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_no_axes_column_decreasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart No Axes Column Decreasing",
            labelEn = "Chart No Axes Column Decreasing",
            tags = listOf("trending down", "chart-no-axes-column-decreasing", "chart no axes column decreasing")
        ),
        IconEntry(
            key = "lucide:chart-no-axes-column-increasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_no_axes_column_increasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart No Axes Column Increasing",
            labelEn = "Chart No Axes Column Increasing",
            tags = listOf("trending up", "chart-no-axes-column-increasing", "chart no axes column increasing")
        ),
        IconEntry(
            key = "lucide:chart-no-axes-column",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_no_axes_column,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart No Axes Column",
            labelEn = "Chart No Axes Column",
            tags = listOf("chart no axes column", "chart-no-axes-column")
        ),
        IconEntry(
            key = "lucide:chart-no-axes-combined",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_no_axes_combined,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart No Axes Combined",
            labelEn = "Chart No Axes Combined",
            tags = listOf("trending up", "chart-no-axes-combined", "chart no axes combined")
        ),
        IconEntry(
            key = "lucide:chart-no-axes-gantt",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_no_axes_gantt,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Chart No Axes Gantt",
            labelEn = "Chart No Axes Gantt",
            tags = listOf("chart-no-axes-gantt", "chart no axes gantt", "intentions", "coding", "code")
        ),
        IconEntry(
            key = "lucide:chart-pie",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_pie,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Pie",
            labelEn = "Chart Pie",
            tags = listOf("chart pie", "chart-pie")
        ),
        IconEntry(
            key = "lucide:chart-quadrant",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_quadrant,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Chart Quadrant",
            labelEn = "Chart Quadrant",
            tags = listOf("2x2", "chart", "quadrant", "chart-scatter", "chart-quadrant", "scatter plot", "chart quadrant")
        ),
        IconEntry(
            key = "lucide:chart-radar",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_radar,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Chart Radar",
            labelEn = "Chart Radar",
            tags = listOf("radar", "shape", "chart radar", "chart", "mineral", "gem", "chart-radar")
        ),
        IconEntry(
            key = "lucide:chart-scatter",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_scatter,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Scatter",
            labelEn = "Chart Scatter",
            tags = listOf("chart scatter", "chart-scatter")
        ),
        IconEntry(
            key = "lucide:chart-spline",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chart_spline,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Chart Spline",
            labelEn = "Chart Spline",
            tags = listOf("polynomial", "continuous", "chart-spline", "quadratic", "curve", "smooth", "chart spline")
        ),
        IconEntry(
            key = "lucide:check-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_check_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Check Check",
            labelEn = "Check Check",
            tags = listOf("check-check", "check check", "double", "received")
        ),
        IconEntry(
            key = "lucide:check-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_check_line,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Check Line",
            labelEn = "Check Line",
            tags = listOf("check line", "check-line")
        ),
        IconEntry(
            key = "lucide:check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Check",
            labelEn = "Check",
            tags = listOf("check")
        ),
        IconEntry(
            key = "lucide:chef-hat",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chef_hat,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Кулинария / Готовка",
            labelEn = "Chef Hat",
            tags = listOf("готовить", "кухня", "restaurant", "повар", "cooking", "food", "chef-hat", "рецепт", "обед", "cook", "chef hat", "kitchen")
        ),
        IconEntry(
            key = "lucide:cherry",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cherry,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Cherry",
            labelEn = "Cherry",
            tags = listOf("cherry", "fruit", "food")
        ),
        IconEntry(
            key = "lucide:chess-bishop",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chess_bishop,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Bishop",
            labelEn = "Chess Bishop",
            tags = listOf("mitre", "chess bishop", "piece", "miter", "chess-bishop", "religion", "board game")
        ),
        IconEntry(
            key = "lucide:chess-king",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chess_king,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Chess King",
            labelEn = "Chess King",
            tags = listOf("chess king", "chess-king", "piece", "stalemate", "crown", "ruler", "board game")
        ),
        IconEntry(
            key = "lucide:chess-knight",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chess_knight,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Knight",
            labelEn = "Chess Knight",
            tags = listOf("chess knight", "piece", "horse", "chess-knight", "board game")
        ),
        IconEntry(
            key = "lucide:chess-pawn",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chess_pawn,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Pawn",
            labelEn = "Chess Pawn",
            tags = listOf("chess pawn", "chess-pawn", "piece", "board game")
        ),
        IconEntry(
            key = "lucide:chess-queen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chess_queen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Queen",
            labelEn = "Chess Queen",
            tags = listOf("chess queen", "piece", "stalemate", "chess-queen", "crown", "ruler", "board game")
        ),
        IconEntry(
            key = "lucide:chess-rook",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_chess_rook,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Rook",
            labelEn = "Chess Rook",
            tags = listOf("castle", "chess rook", "piece", "chess-rook", "board game")
        ),
        IconEntry(
            key = "lucide:church",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_church,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Church",
            labelEn = "Church",
            tags = listOf("church", "building", "temple")
        ),
        IconEntry(
            key = "lucide:cigarette-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cigarette_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Без курения",
            labelEn = "Cigarette Off",
            tags = listOf("бросить", "не курить", "без сигарет", "no-smoking", "зож", "quit smoking", "cigarette-off", "cigarette off")
        ),
        IconEntry(
            key = "lucide:cigarette",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cigarette,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Cigarette",
            labelEn = "Cigarette",
            tags = listOf("smoking", "cigarette")
        ),
        IconEntry(
            key = "lucide:circle-alert",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_alert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Alert",
            labelEn = "Circle Alert",
            tags = listOf("circle alert", "circle-alert")
        ),
        IconEntry(
            key = "lucide:circle-check-big",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_check_big,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Check Big",
            labelEn = "Circle Check Big",
            tags = listOf("circle check big", "circle-check-big")
        ),
        IconEntry(
            key = "lucide:circle-dashed-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_dashed_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Dashed Check",
            labelEn = "Circle Dashed Check",
            tags = listOf("changes", "published", "schedule", "circle dashed check", "revision", "circle-dashed-check", "reapproval")
        ),
        IconEntry(
            key = "lucide:circle-dashed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_dashed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Circle Dashed",
            labelEn = "Circle Dashed",
            tags = listOf("issue", "dot", "circle dashed", "circle-dashed", "draft", "pending", "progress")
        ),
        IconEntry(
            key = "lucide:circle-divide",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_divide,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Divide",
            labelEn = "Circle Divide",
            tags = listOf("÷", "math", "circle divide", "/", "circle-divide", "calculate")
        ),
        IconEntry(
            key = "lucide:circle-dollar-sign",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_dollar_sign,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Circle Dollar Sign",
            labelEn = "Circle Dollar Sign",
            tags = listOf("circle dollar sign", "circle-dollar-sign", "marketing")
        ),
        IconEntry(
            key = "lucide:circle-dot-dashed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_dot_dashed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Dot Dashed",
            labelEn = "Circle Dot Dashed",
            tags = listOf("issue", "circle dot dashed", "code", "draft", "circle-dot-dashed", "pending", "progress")
        ),
        IconEntry(
            key = "lucide:circle-dot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_dot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Circle Dot",
            labelEn = "Circle Dot",
            tags = listOf("issue", "circle-dot", "code", "coding", "pending", "progress", "circle dot")
        ),
        IconEntry(
            key = "lucide:circle-ellipsis",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_ellipsis,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Circle Ellipsis",
            labelEn = "Circle Ellipsis",
            tags = listOf("loader", "et cetera", "circle ellipsis", "etc", "ellipsis", "circle-ellipsis", "loading")
        ),
        IconEntry(
            key = "lucide:circle-equal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_equal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Equal",
            labelEn = "Circle Equal",
            tags = listOf("=", "circle-equal", "shape", "circle equal", "calculate")
        ),
        IconEntry(
            key = "lucide:circle-euro",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_euro,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Circle Euro",
            labelEn = "Circle Euro",
            tags = listOf("circle-euro", "€", "euro", "symbol", "coin", "circle euro", "europe")
        ),
        IconEntry(
            key = "lucide:circle-fading-play",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_fading_play,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Circle Fading Play",
            labelEn = "Circle Fading Play",
            tags = listOf("circle fading play", "reels", "shorts", "video", "stories", "circle-fading-play", "short clips")
        ),
        IconEntry(
            key = "lucide:circle-fading-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_fading_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Circle Fading Plus",
            labelEn = "Circle Fading Plus",
            tags = listOf("circle fading plus", "content", "social media", "stories", "sharing", "circle-fading-plus")
        ),
        IconEntry(
            key = "lucide:circle-gauge",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_gauge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Circle Gauge",
            labelEn = "Circle Gauge",
            tags = listOf("circle gauge", "pressure", "meter", "dial", "dashboard", "circle-gauge", "speed")
        ),
        IconEntry(
            key = "lucide:circle-letter-p-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_letter_p_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Letter P Off",
            labelEn = "Circle Letter P Off",
            tags = listOf("no parking", "circle letter p off", "circle-letter-p-off")
        ),
        IconEntry(
            key = "lucide:circle-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Minus",
            labelEn = "Circle Minus",
            tags = listOf("circle minus", "operator", "circle-minus", "decrease", "line", "code", "calculate")
        ),
        IconEntry(
            key = "lucide:circle-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Off",
            labelEn = "Circle Off",
            tags = listOf("null", "diameter", "circle off", "ø", "zero", "nothing", "circle-off")
        ),
        IconEntry(
            key = "lucide:circle-pause",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_pause,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Circle Pause",
            labelEn = "Circle Pause",
            tags = listOf("audio", "music", "circle-pause", "circle pause")
        ),
        IconEntry(
            key = "lucide:circle-percent",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_percent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Circle Percent",
            labelEn = "Circle Percent",
            tags = listOf("circle percent", "circle-percent", "unverified", "sticker", "verified")
        ),
        IconEntry(
            key = "lucide:circle-pile",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_pile,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Pile",
            labelEn = "Circle Pile",
            tags = listOf("circle", "circle pile", "layer", "pile", "stack", "circle-pile")
        ),
        IconEntry(
            key = "lucide:circle-play",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_play,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Circle Play",
            labelEn = "Circle Play",
            tags = listOf("music", "circle play", "circle-play")
        ),
        IconEntry(
            key = "lucide:circle-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Circle Plus",
            labelEn = "Circle Plus",
            tags = listOf("crosshair", "positive", "circle plus", "aim", "target", "circle-plus", "calculate")
        ),
        IconEntry(
            key = "lucide:circle-pound-sterling",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_pound_sterling,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Circle Pound Sterling",
            labelEn = "Circle Pound Sterling",
            tags = listOf("circle-pound-sterling", "british", "coin", "circle pound sterling", "gbp", "marketing", "penny")
        ),
        IconEntry(
            key = "lucide:circle-power",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_power,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Power",
            labelEn = "Circle Power",
            tags = listOf("on", "circle-power", "switch", "toggle", "circle power", "device", "off")
        ),
        IconEntry(
            key = "lucide:circle-question-mark",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_question_mark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Question Mark",
            labelEn = "Circle Question Mark",
            tags = listOf("circle question mark", "circle-question-mark")
        ),
        IconEntry(
            key = "lucide:circle-slash",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_slash,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Circle Slash",
            labelEn = "Circle Slash",
            tags = listOf("circle-slash", "diameter", "ø", "circle slash", "zero", "nothing", "null")
        ),
        IconEntry(
            key = "lucide:circle-small",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_small,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle Small",
            labelEn = "Circle Small",
            tags = listOf("circle small", "genderless", "shape", "circle-small", "gender", "bullet")
        ),
        IconEntry(
            key = "lucide:circle-star",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_star,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Circle Star",
            labelEn = "Circle Star",
            tags = listOf("circle star", "honour", "order", "decoration", "medal", "badge", "circle-star")
        ),
        IconEntry(
            key = "lucide:circle-stop",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_stop,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Circle Stop",
            labelEn = "Circle Stop",
            tags = listOf("circle stop", "music", "circle-stop", "media")
        ),
        IconEntry(
            key = "lucide:circle-user-round",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_user_round,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle User Round",
            labelEn = "Circle User Round",
            tags = listOf("circle user round", "circle-user-round")
        ),
        IconEntry(
            key = "lucide:circle-user",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_user,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Circle User",
            labelEn = "Circle User",
            tags = listOf("circle-user", "circle user")
        ),
        IconEntry(
            key = "lucide:circle-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle X",
            labelEn = "Circle X",
            tags = listOf("circle-x", "failure", "circle x", "error", "mistake", "incorrect", "wrong")
        ),
        IconEntry(
            key = "lucide:circle",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Circle",
            labelEn = "Circle",
            tags = listOf("record", "circle", "shape", "zero", "off")
        ),
        IconEntry(
            key = "lucide:circuit-board",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_circuit_board,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Circuit Board",
            labelEn = "Circuit Board",
            tags = listOf("electronics", "circuit board", "electricity", "circuit-board", "computing")
        ),
        IconEntry(
            key = "lucide:citrus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_citrus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Citrus",
            labelEn = "Citrus",
            tags = listOf("lemon", "fruit", "orange", "grapefruit", "citrus")
        ),
        IconEntry(
            key = "lucide:clapperboard",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clapperboard,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Clapperboard",
            labelEn = "Clapperboard",
            tags = listOf("film", "video", "cinema", "clapperboard", "camera", "movie")
        ),
        IconEntry(
            key = "lucide:clef-alto",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clef_alto,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Clef Alto",
            labelEn = "Clef Alto",
            tags = listOf("clef-alto", "music", "key", "do", "c", "note", "clef alto")
        ),
        IconEntry(
            key = "lucide:clef-bass",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clef_bass,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Clef Bass",
            labelEn = "Clef Bass",
            tags = listOf("music", "key", "f", "fa", "note", "clef-bass", "clef bass")
        ),
        IconEntry(
            key = "lucide:clef-treble",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clef_treble,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Clef Treble",
            labelEn = "Clef Treble",
            tags = listOf("music", "clef-treble", "key", "note", "sol", "g", "clef treble")
        ),
        IconEntry(
            key = "lucide:clipboard-clock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Clock",
            labelEn = "Clipboard Clock",
            tags = listOf("clipboard-clock", "reminder", "time tracking", "log", "alarm", "scheduled", "clipboard clock")
        ),
        IconEntry(
            key = "lucide:clipboard-copy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_copy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Copy",
            labelEn = "Clipboard Copy",
            tags = listOf("clipboard-copy", "clipboard copy")
        ),
        IconEntry(
            key = "lucide:clipboard-list",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_list,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard List",
            labelEn = "Clipboard List",
            tags = listOf("clipboard list", "clipboard-list", "tasks")
        ),
        IconEntry(
            key = "lucide:clipboard-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Minus",
            labelEn = "Clipboard Minus",
            tags = listOf("medical", "document", "doctor", "clipboard minus", "report", "clipboard-minus")
        ),
        IconEntry(
            key = "lucide:clipboard-paste",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_paste,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Paste",
            labelEn = "Clipboard Paste",
            tags = listOf("clipboard-paste", "clipboard paste")
        ),
        IconEntry(
            key = "lucide:clipboard-pen-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_pen_line,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Pen Line",
            labelEn = "Clipboard Pen Line",
            tags = listOf("clipboard-pen-line", "clipboard pen line")
        ),
        IconEntry(
            key = "lucide:clipboard-pen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_pen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Pen",
            labelEn = "Clipboard Pen",
            tags = listOf("signature", "clipboard-pen", "clipboard pen")
        ),
        IconEntry(
            key = "lucide:clipboard-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Clipboard Plus",
            labelEn = "Clipboard Plus",
            tags = listOf("clipboard plus", "medical", "document", "doctor", "report", "clipboard-plus")
        ),
        IconEntry(
            key = "lucide:clipboard-type",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_type,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard Type",
            labelEn = "Clipboard Type",
            tags = listOf("format", "clipboard type", "clipboard-type")
        ),
        IconEntry(
            key = "lucide:clipboard-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard X",
            labelEn = "Clipboard X",
            tags = listOf("clipboard x", "clipboard-x")
        ),
        IconEntry(
            key = "lucide:clipboard",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clipboard,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clipboard",
            labelEn = "Clipboard",
            tags = listOf("paste", "clipboard", "copy")
        ),
    )

    private fun getPart6(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:clock-10",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_10,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 10",
            labelEn = "Clock 10",
            tags = listOf("clock-10", "clock 10")
        ),
        IconEntry(
            key = "lucide:clock-11",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_11,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 11",
            labelEn = "Clock 11",
            tags = listOf("clock-11", "clock 11")
        ),
        IconEntry(
            key = "lucide:clock-12",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_12,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 12",
            labelEn = "Clock 12",
            tags = listOf("midnight", "clock 12", "clock-12", "noon")
        ),
        IconEntry(
            key = "lucide:clock-5",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_5,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 5",
            labelEn = "Clock 5",
            tags = listOf("clock-5", "clock 5")
        ),
        IconEntry(
            key = "lucide:clock-6",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_6,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 6",
            labelEn = "Clock 6",
            tags = listOf("clock 6", "clock-6")
        ),
        IconEntry(
            key = "lucide:clock-7",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_7,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 7",
            labelEn = "Clock 7",
            tags = listOf("clock 7", "clock-7")
        ),
        IconEntry(
            key = "lucide:clock-8",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_8,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 8",
            labelEn = "Clock 8",
            tags = listOf("clock-8", "clock 8")
        ),
        IconEntry(
            key = "lucide:clock-9",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_9,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock 9",
            labelEn = "Clock 9",
            tags = listOf("clock-9", "clock 9")
        ),
        IconEntry(
            key = "lucide:clock-alert",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_alert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Alert",
            labelEn = "Clock Alert",
            tags = listOf("clock-alert", "wrong", "clock alert")
        ),
        IconEntry(
            key = "lucide:clock-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Arrow Down",
            labelEn = "Clock Arrow Down",
            tags = listOf("clock arrow down", "clock-arrow-down")
        ),
        IconEntry(
            key = "lucide:clock-arrow-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_arrow_left,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Arrow Left",
            labelEn = "Clock Arrow Left",
            tags = listOf("range", "clock arrow left", "assign", "clock-arrow-left")
        ),
        IconEntry(
            key = "lucide:clock-arrow-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_arrow_right,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Arrow Right",
            labelEn = "Clock Arrow Right",
            tags = listOf("clock arrow right", "range", "unassign", "clock-arrow-right")
        ),
        IconEntry(
            key = "lucide:clock-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Arrow Up",
            labelEn = "Clock Arrow Up",
            tags = listOf("clock arrow up", "clock-arrow-up")
        ),
        IconEntry(
            key = "lucide:clock-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Check",
            labelEn = "Clock Check",
            tags = listOf("clock check", "clock-check")
        ),
        IconEntry(
            key = "lucide:clock-fading",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_fading,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Fading",
            labelEn = "Clock Fading",
            tags = listOf("clock fading", "clock-fading")
        ),
        IconEntry(
            key = "lucide:clock-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clock_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Clock Plus",
            labelEn = "Clock Plus",
            tags = listOf("clock plus", "clock-plus")
        ),
        IconEntry(
            key = "lucide:closed-caption",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_closed_caption,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Closed Caption",
            labelEn = "Closed Caption",
            tags = listOf("closed captions", "closed-caption", "tv", "video", "subtitles", "movie", "closed caption")
        ),
        IconEntry(
            key = "lucide:cloud-alert",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_alert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Cloud Alert",
            labelEn = "Cloud Alert",
            tags = listOf("cloud-alert", "cloud alert", "network", "sync")
        ),
        IconEntry(
            key = "lucide:cloud-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Cloud Check",
            labelEn = "Cloud Check",
            tags = listOf("saved", "cloud-check", "persisted", "cloud check", "network", "sync")
        ),
        IconEntry(
            key = "lucide:cloud-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Cloud Cog",
            labelEn = "Cloud Cog",
            tags = listOf("cloud cog", "ai", "cluster", "cloud-cog", "network", "computing")
        ),
        IconEntry(
            key = "lucide:cloud-drizzle",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_drizzle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Drizzle",
            labelEn = "Cloud Drizzle",
            tags = listOf("cloud-drizzle", "shower", "cloud drizzle")
        ),
        IconEntry(
            key = "lucide:cloud-fog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_fog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Fog",
            labelEn = "Cloud Fog",
            tags = listOf("cloud fog", "mist", "cloud-fog")
        ),
        IconEntry(
            key = "lucide:cloud-hail",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_hail,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Hail",
            labelEn = "Cloud Hail",
            tags = listOf("rainfall", "cloud-hail", "cloud hail")
        ),
        IconEntry(
            key = "lucide:cloud-lightning",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_lightning,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Lightning",
            labelEn = "Cloud Lightning",
            tags = listOf("cloud-lightning", "bolt", "cloud lightning")
        ),
        IconEntry(
            key = "lucide:cloud-moon-rain",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_moon_rain,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Moon Rain",
            labelEn = "Cloud Moon Rain",
            tags = listOf("rainfall", "cloud moon rain", "cloud-moon-rain", "partly")
        ),
        IconEntry(
            key = "lucide:cloud-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Off",
            labelEn = "Cloud Off",
            tags = listOf("cloud off", "disconnect", "cloud-off")
        ),
        IconEntry(
            key = "lucide:cloud-rain-wind",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_rain_wind,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Rain Wind",
            labelEn = "Cloud Rain Wind",
            tags = listOf("cloud-rain-wind", "cloud rain wind")
        ),
        IconEntry(
            key = "lucide:cloud-rain",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_rain,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Rain",
            labelEn = "Cloud Rain",
            tags = listOf("rainfall", "cloud rain", "cloud-rain")
        ),
        IconEntry(
            key = "lucide:cloud-snow",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_snow,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Snow",
            labelEn = "Cloud Snow",
            tags = listOf("cloud-snow", "cloud snow", "blizzard")
        ),
        IconEntry(
            key = "lucide:cloud-sun-rain",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_sun_rain,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Sun Rain",
            labelEn = "Cloud Sun Rain",
            tags = listOf("rainfall", "cloud-sun-rain", "cloud sun rain")
        ),
        IconEntry(
            key = "lucide:cloud-sun",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloud_sun,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cloud Sun",
            labelEn = "Cloud Sun",
            tags = listOf("cloud sun", "cloud-sun", "partly")
        ),
        IconEntry(
            key = "lucide:cloudy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cloudy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Cloudy",
            labelEn = "Cloudy",
            tags = listOf("cloudy", "clouds", "weather")
        ),
        IconEntry(
            key = "lucide:clover",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_clover,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Clover",
            labelEn = "Clover",
            tags = listOf("clover", "leaf", "luck", "plant")
        ),
        IconEntry(
            key = "lucide:club",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_club,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Club",
            labelEn = "Club",
            tags = listOf("club", "shape", "cards", "playing", "suit")
        ),
        IconEntry(
            key = "lucide:code",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_code,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Code",
            labelEn = "Code",
            tags = listOf("xml", "code", "source", "html")
        ),
        IconEntry(
            key = "lucide:cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Cog",
            labelEn = "Cog",
            tags = listOf("cog", "construction", "build", "parts", "fixed", "computing")
        ),
        IconEntry(
            key = "lucide:combine",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_combine,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Combine",
            labelEn = "Combine",
            tags = listOf("cubes", "collection", "packages", "units", "parts", "combine")
        ),
        IconEntry(
            key = "lucide:command",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_command,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Command",
            labelEn = "Command",
            tags = listOf("key", "button", "command", "mac", "keyboard", "cmd")
        ),
        IconEntry(
            key = "lucide:component",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_component,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Component",
            labelEn = "Component",
            tags = listOf("group", "element", "part", "component", "module", "design")
        ),
        IconEntry(
            key = "lucide:computer",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_computer,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Computer",
            labelEn = "Computer",
            tags = listOf("pc", "chassis", "codespaces", "computer")
        ),
        IconEntry(
            key = "lucide:concierge-bell",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_concierge_bell,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Concierge Bell",
            labelEn = "Concierge Bell",
            tags = listOf("reception", "concierge-bell", "porter", "concierge bell", "bell")
        ),
        IconEntry(
            key = "lucide:cone",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cone",
            labelEn = "Cone",
            tags = listOf("filter", "cone", "triangle", "triangular", "conical", "geometry")
        ),
        IconEntry(
            key = "lucide:construction",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_construction,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Construction",
            labelEn = "Construction",
            tags = listOf("roadwork", "construction", "maintenance", "blockade", "barricade")
        ),
        IconEntry(
            key = "lucide:contact-round",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_contact_round,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Contact Round",
            labelEn = "Contact Round",
            tags = listOf("contact-round", "contact round")
        ),
        IconEntry(
            key = "lucide:contact",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_contact,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Contact",
            labelEn = "Contact",
            tags = listOf("acquaintance", "family", "user", "contact", "person", "friend")
        ),
        IconEntry(
            key = "lucide:container",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_container,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Container",
            labelEn = "Container",
            tags = listOf("container", "environment", "storage", "shipping", "supply chain", "freight")
        ),
        IconEntry(
            key = "lucide:contrast",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_contrast,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Contrast",
            labelEn = "Contrast",
            tags = listOf("accessibility", "contrast", "display")
        ),
        IconEntry(
            key = "lucide:cooking-pot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cooking_pot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Cooking Pot",
            labelEn = "Cooking Pot",
            tags = listOf("pod", "cooking pot", "cooking", "food", "kitchen", "cooking-pot", "recipe")
        ),
        IconEntry(
            key = "lucide:copy-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copy_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copy Check",
            labelEn = "Copy Check",
            tags = listOf("copy-check", "copy check")
        ),
        IconEntry(
            key = "lucide:copy-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copy_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copy Minus",
            labelEn = "Copy Minus",
            tags = listOf("collapse", "copy-minus", "copy minus")
        ),
        IconEntry(
            key = "lucide:copy-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copy_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copy Plus",
            labelEn = "Copy Plus",
            tags = listOf("expand", "copy plus", "copy-plus")
        ),
        IconEntry(
            key = "lucide:copy-slash",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copy_slash,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copy Slash",
            labelEn = "Copy Slash",
            tags = listOf("division", "divide", "no", "copy-slash", "stop", "copy slash", "error")
        ),
        IconEntry(
            key = "lucide:copy-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copy_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copy X",
            labelEn = "Copy X",
            tags = listOf("copy-x", "multiply", "copy x", "multiplication")
        ),
        IconEntry(
            key = "lucide:copy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copy",
            labelEn = "Copy",
            tags = listOf("clone", "multiple", "copy", "duplicate")
        ),
        IconEntry(
            key = "lucide:copyleft",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_copyleft,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Copyleft",
            labelEn = "Copyleft",
            tags = listOf("licence", "copyleft")
        ),
        IconEntry(
            key = "lucide:cpu-ai",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cpu_ai,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Cpu Ai",
            labelEn = "Cpu Ai",
            tags = listOf("machine learning", "neural network", "cpu-ai", "ai", "cpu ai", "automation", "artificial intelligence")
        ),
        IconEntry(
            key = "lucide:creative-commons",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_creative_commons,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Creative Commons",
            labelEn = "Creative Commons",
            tags = listOf("creative-commons", "licence", "creative commons", "license")
        ),
        IconEntry(
            key = "lucide:credit-card-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_credit_card_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Check",
            labelEn = "Credit Card Check",
            tags = listOf("credit-card-check", "credit card check", "debit", "banking", "transaction", "finance", "wallet")
        ),
        IconEntry(
            key = "lucide:credit-card-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_credit_card_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Minus",
            labelEn = "Credit Card Minus",
            tags = listOf("canceled", "credit-card-minus", "unlink", "revoke", "debit", "credit card minus", "decline")
        ),
        IconEntry(
            key = "lucide:credit-card-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_credit_card_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Plus",
            labelEn = "Credit Card Plus",
            tags = listOf("credit-card-plus", "credit card plus", "debit", "banking", "transaction", "finance", "wallet")
        ),
        IconEntry(
            key = "lucide:credit-card-reader",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_credit_card_reader,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Reader",
            labelEn = "Credit Card Reader",
            tags = listOf("credit card reader", "kiosk", "credit-card-reader", "atm", "terminal", "pos", "checkout")
        ),
        IconEntry(
            key = "lucide:credit-card-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_credit_card_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card X",
            labelEn = "Credit Card X",
            tags = listOf("credit-card-x", "credit card x", "banking", "debit", "transaction", "finance", "wallet")
        ),
        IconEntry(
            key = "lucide:croissant",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_croissant,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Croissant",
            labelEn = "Croissant",
            tags = listOf("food", "bakery", "pastry", "croissant", "cooking")
        ),
        IconEntry(
            key = "lucide:crop",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_crop,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Crop",
            labelEn = "Crop",
            tags = listOf("crop", "image", "photo")
        ),
        IconEntry(
            key = "lucide:crosshair",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_crosshair,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Фокус / Цель",
            labelEn = "Crosshair",
            tags = listOf("фокус", "цель", "концентрация", "crosshair", "focus", "aim", "target", "прицел")
        ),
        IconEntry(
            key = "lucide:cuboid",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cuboid,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Cuboid",
            labelEn = "Cuboid",
            tags = listOf("solid", "brick", "block", "box", "3d", "cuboid")
        ),
        IconEntry(
            key = "lucide:cupcake",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cupcake,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Cupcake",
            labelEn = "Cupcake",
            tags = listOf("celebration", "cupcake", "dessert", "food", "cake", "sweet")
        ),
        IconEntry(
            key = "lucide:currency",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_currency,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Currency",
            labelEn = "Currency",
            tags = listOf("money", "currency", "finance")
        ),
        IconEntry(
            key = "lucide:cylinder",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_cylinder,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Cylinder",
            labelEn = "Cylinder",
            tags = listOf("cylinder", "container", "shape", "storage", "geometry", "elliptical")
        ),
        IconEntry(
            key = "lucide:dam",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dam,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Dam",
            labelEn = "Dam",
            tags = listOf("dam", "energy", "electricity", "water")
        ),
        IconEntry(
            key = "lucide:database-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Arrow Down",
            labelEn = "Database Arrow Down",
            tags = listOf("database arrow down", "downsize", "database-arrow-down", "download", "pull")
        ),
        IconEntry(
            key = "lucide:database-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Arrow Up",
            labelEn = "Database Arrow Up",
            tags = listOf("upload", "database arrow up", "push", "upscale", "database-arrow-up")
        ),
        IconEntry(
            key = "lucide:database-backup",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_backup,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Backup",
            labelEn = "Database Backup",
            tags = listOf("database-backup", "rotate", "arrow", "time machine", "database backup", "left")
        ),
        IconEntry(
            key = "lucide:database-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Check",
            labelEn = "Database Check",
            tags = listOf("database check", "database-check")
        ),
        IconEntry(
            key = "lucide:database-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Database Cog",
            labelEn = "Database Cog",
            tags = listOf("db", "settings", "database-cog", "database cog", "configuration", "gear", "preferences")
        ),
        IconEntry(
            key = "lucide:database-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Minus",
            labelEn = "Database Minus",
            tags = listOf("database minus", "database-minus")
        ),
        IconEntry(
            key = "lucide:database-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Plus",
            labelEn = "Database Plus",
            tags = listOf("expand", "database-plus", "database plus")
        ),
        IconEntry(
            key = "lucide:database-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_search,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Search",
            labelEn = "Database Search",
            tags = listOf("database-search", "database search")
        ),
        IconEntry(
            key = "lucide:database-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database X",
            labelEn = "Database X",
            tags = listOf("rejected", "failed", "database x", "invalid", "denied", "error", "database-x")
        ),
        IconEntry(
            key = "lucide:database-zap",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_database_zap,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Database Zap",
            labelEn = "Database Zap",
            tags = listOf("database-zap", "cache busting", "crash", "database zap")
        ),
        IconEntry(
            key = "lucide:dessert",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dessert,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Dessert",
            labelEn = "Dessert",
            tags = listOf("custard", "dessert", "iced bun", "christmas", "xmas", "pudding")
        ),
        IconEntry(
            key = "lucide:diameter",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_diameter,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Diameter",
            labelEn = "Diameter",
            tags = listOf("circle", "shape", "width", "trigonometry", "diameter", "geometry")
        ),
        IconEntry(
            key = "lucide:diamond-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_diamond_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Diamond Minus",
            labelEn = "Diamond Minus",
            tags = listOf("keyframe", "button", "decrease", "calculator", "diamond minus", "diamond-minus", "keyboard")
        ),
        IconEntry(
            key = "lucide:diamond-percent",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_diamond_percent,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Diamond Percent",
            labelEn = "Diamond Percent",
            tags = listOf("unverified", "sticker", "diamond-percent", "verified", "diamond percent")
        ),
        IconEntry(
            key = "lucide:diamond-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_diamond_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Diamond Plus",
            labelEn = "Diamond Plus",
            tags = listOf("diamond-plus", "keyframe", "diamond plus", "positive", "toolbar", "crosshair", "calculate")
        ),
        IconEntry(
            key = "lucide:diamond",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_diamond,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Diamond",
            labelEn = "Diamond",
            tags = listOf("shape", "oblique", "square", "diamond", "rectangle", "rhombus")
        ),
        IconEntry(
            key = "lucide:dice-5",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dice_5,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Кубик / Настолки",
            labelEn = "Dice 5",
            tags = listOf("5", "игра", "настолки", "tabletop", "вечер", "random", "dice 5", "board", "кубик", "dice-5", "dice")
        ),
        IconEntry(
            key = "lucide:dice-6",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dice_6,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 6",
            labelEn = "Dice 6",
            tags = listOf("dice 6", "6", "tabletop", "random", "board", "dice-6", "dice")
        ),
        IconEntry(
            key = "lucide:dices",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dices,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Dices",
            labelEn = "Dices",
            tags = listOf("tabletop", "random", "board", "dices", "game", "dice")
        ),
        IconEntry(
            key = "lucide:diff",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_diff,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Diff",
            labelEn = "Diff",
            tags = listOf("patch", "plus", "minus", "diff", "difference", "compare")
        ),
        IconEntry(
            key = "lucide:disc-album",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_disc_album,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Disc Album",
            labelEn = "Disc Album",
            tags = listOf("disc-album", "disc album")
        ),
        IconEntry(
            key = "lucide:disc",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_disc,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Disc",
            labelEn = "Disc",
            tags = listOf("songs", "music", "disc", "format", "album", "cd")
        ),
        IconEntry(
            key = "lucide:divide",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_divide,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Divide",
            labelEn = "Divide",
            tags = listOf("operator", "division", "divide", "math", "code", "calculate")
        ),
        IconEntry(
            key = "lucide:dna-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dna_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Dna Off",
            labelEn = "Dna Off",
            tags = listOf("dna-off", "gmo free", "dna off")
        ),
        IconEntry(
            key = "lucide:dock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Dock",
            labelEn = "Dock",
            tags = listOf("applications", "menu bar", "launch", "dock", "desktop", "home")
        ),
        IconEntry(
            key = "lucide:dome",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dome,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Dome",
            labelEn = "Dome",
            tags = listOf("dome", "building", "roof", "shelter", "rotunda", "cupola")
        ),
        IconEntry(
            key = "lucide:donut",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_donut,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Donut",
            labelEn = "Donut",
            tags = listOf("junk food", "topping", "sprinkles", "doughnut", "donut", "fast food")
        ),
        IconEntry(
            key = "lucide:door-closed-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_door_closed_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Door Closed Cog",
            labelEn = "Door Closed Cog",
            tags = listOf("automation", "access", "door closed cog", "door-closed-cog", "room")
        ),
        IconEntry(
            key = "lucide:door-closed-locked",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_door_closed_locked,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Door Closed Locked",
            labelEn = "Door Closed Locked",
            tags = listOf("door closed locked", "door-closed-locked")
        ),
        IconEntry(
            key = "lucide:door-closed-package",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_door_closed_package,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Door Closed Package",
            labelEn = "Door Closed Package",
            tags = listOf("delivery", "shipping", "door closed package", "parcel", "door-closed-package", "doorstep", "drop-off")
        ),
    )

    private fun getPart7(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:door-open",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_door_open,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Door Open",
            labelEn = "Door Open",
            tags = listOf("ingress", "entrance", "exit", "entry", "egress", "door-open", "door open")
        ),
        IconEntry(
            key = "lucide:door-stairwell",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_door_stairwell,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Door Stairwell",
            labelEn = "Door Stairwell",
            tags = listOf("stairs", "door-stairwell", "staircase", "steps", "door stairwell", "ladder", "stairway")
        ),
        IconEntry(
            key = "lucide:dot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_dot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Dot",
            labelEn = "Dot",
            tags = listOf("step", "punctuation", "middot", "interpoint", "dot", "interpunct")
        ),
        IconEntry(
            key = "lucide:drafting-compass",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_drafting_compass,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Drafting Compass",
            labelEn = "Drafting Compass",
            tags = listOf("circumference", "trigonometry", "diameter", "drafting-compass", "drafting compass", "radius", "geometry")
        ),
        IconEntry(
            key = "lucide:drama",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_drama,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Drama",
            labelEn = "Drama",
            tags = listOf("entertainment", "drama", "theatre", "masks", "theater")
        ),
        IconEntry(
            key = "lucide:drill",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_drill,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Drill",
            labelEn = "Drill",
            tags = listOf("diy", "power", "drill", "bit", "head", "hole")
        ),
        IconEntry(
            key = "lucide:drone",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_drone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Drone",
            labelEn = "Drone",
            tags = listOf("drone", "uav", "aerial", "quadcopter", "flight", "flying")
        ),
        IconEntry(
            key = "lucide:droplet-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_droplet_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Droplet Off",
            labelEn = "Droplet Off",
            tags = listOf("droplet-off", "droplet off")
        ),
        IconEntry(
            key = "lucide:droplets",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_droplets,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Droplets",
            labelEn = "Droplets",
            tags = listOf("weather", "droplets", "water", "wet", "fluid", "liquid")
        ),
        IconEntry(
            key = "lucide:drum",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_drum,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Drum",
            labelEn = "Drum",
            tags = listOf("drummer", "instrument", "sticks", "kit", "beat", "drum")
        ),
        IconEntry(
            key = "lucide:drumstick",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_drumstick,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Drumstick",
            labelEn = "Drumstick",
            tags = listOf("food", "chicken", "drumstick", "meat")
        ),
        IconEntry(
            key = "lucide:ear-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ear_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Ear Off",
            labelEn = "Ear Off",
            tags = listOf("ear off", "ear-off", "silence", "hearing loss", "hard of hearing", "deafness")
        ),
        IconEntry(
            key = "lucide:ear",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ear,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Ear",
            labelEn = "Ear",
            tags = listOf("hearing", "noise", "ear", "accessibility", "audio")
        ),
        IconEntry(
            key = "lucide:earbuds-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_earbuds_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Earbuds Off",
            labelEn = "Earbuds Off",
            tags = listOf("sound", "earphones", "wireless", "audio", "earbuds-off", "bluetooth", "earbuds off")
        ),
        IconEntry(
            key = "lucide:earbuds",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_earbuds,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Earbuds",
            labelEn = "Earbuds",
            tags = listOf("sound", "earbuds", "earphones", "wireless", "bluetooth", "audio")
        ),
        IconEntry(
            key = "lucide:earth-lock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_earth_lock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Earth Lock",
            labelEn = "Earth Lock",
            tags = listOf("connection", "encryption", "earth-lock", "network", "vpn", "earth lock")
        ),
        IconEntry(
            key = "lucide:earth",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_earth,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Earth",
            labelEn = "Earth",
            tags = listOf("earth", "globe", "translate", "world", "language", "browser")
        ),
        IconEntry(
            key = "lucide:eclipse",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eclipse,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Eclipse",
            labelEn = "Eclipse",
            tags = listOf("eclipse", "earth", "lunar", "crescent moon", "solar", "sun")
        ),
        IconEntry(
            key = "lucide:egg-fried",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_egg_fried,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Egg Fried",
            labelEn = "Egg Fried",
            tags = listOf("food", "breakfast", "egg-fried", "egg fried")
        ),
        IconEntry(
            key = "lucide:egg-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_egg_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Egg Off",
            labelEn = "Egg Off",
            tags = listOf("hatched", "vegan", "egg free", "bad egg", "egg-off", "egg off")
        ),
        IconEntry(
            key = "lucide:egg",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_egg,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Egg",
            labelEn = "Egg",
            tags = listOf("hatch", "bird", "shell", "egg", "chicken", "nest")
        ),
        IconEntry(
            key = "lucide:eighth-note",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eighth_note,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Eighth Note",
            labelEn = "Eighth Note",
            tags = listOf("sound", "music", "note", "eighth note", "quaver", "audio", "eighth-note")
        ),
        IconEntry(
            key = "lucide:eject",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eject,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Eject",
            labelEn = "Eject",
            tags = listOf("disc", "blu-ray", "eject", "drive", "cd", "dvd")
        ),
        IconEntry(
            key = "lucide:ellipse",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ellipse,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Ellipse",
            labelEn = "Ellipse",
            tags = listOf("geometry", "shape", "rounded", "outline", "ellipse", "smooth")
        ),
        IconEntry(
            key = "lucide:ellipsis-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ellipsis_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Ellipsis Vertical",
            labelEn = "Ellipsis Vertical",
            tags = listOf("more", "options", "further", "menu", "ellipsis vertical", "spread", "ellipsis-vertical")
        ),
        IconEntry(
            key = "lucide:ellipsis",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ellipsis,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Ellipsis",
            labelEn = "Ellipsis",
            tags = listOf("loader", "et cetera", "etc", "ellipsis", "loading", "progress")
        ),
        IconEntry(
            key = "lucide:emirati-dirham",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_emirati_dirham,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Emirati Dirham",
            labelEn = "Emirati Dirham",
            tags = listOf("united arab emirates", "uae", "aed", "emirati-dirham", "middle east", "arab emirates dirham", "emirati dirham")
        ),
        IconEntry(
            key = "lucide:engine",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_engine,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Engine",
            labelEn = "Engine",
            tags = listOf("repair", "power", "drive", "energy", "engine", "vehicle")
        ),
        IconEntry(
            key = "lucide:equal-approximately-not",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_equal_approximately_not,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Equal Approximately Not",
            labelEn = "Equal Approximately Not",
            tags = listOf("unequal", "equal approximately not", "≇", "not", "tolerance", "approximately", "equal-approximately-not")
        ),
        IconEntry(
            key = "lucide:equal-approximately",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_equal_approximately,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Equal Approximately",
            labelEn = "Equal Approximately",
            tags = listOf("equal approximately", "about", "equal-approximately")
        ),
        IconEntry(
            key = "lucide:equal-not",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_equal_not,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Equal Not",
            labelEn = "Equal Not",
            tags = listOf("off", "equal-not", "≠", "equal not")
        ),
        IconEntry(
            key = "lucide:equal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_equal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Equal",
            labelEn = "Equal",
            tags = listOf("operator", "equal", "math", "assignment", "code", "calculate")
        ),
        IconEntry(
            key = "lucide:eraser",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eraser,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Eraser",
            labelEn = "Eraser",
            tags = listOf("pencil", "drawing", "eraser", "undo", "trash")
        ),
        IconEntry(
            key = "lucide:ethernet-port",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ethernet_port,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Ethernet Port",
            labelEn = "Ethernet Port",
            tags = listOf("connection", "ethernet-port", "cable", "internet", "lan", "ethernet port", "network")
        ),
        IconEntry(
            key = "lucide:euro",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_euro,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Euro",
            labelEn = "Euro",
            tags = listOf("euro")
        ),
        IconEntry(
            key = "lucide:ev-charger",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ev_charger,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Ev Charger",
            labelEn = "Ev Charger",
            tags = listOf("ev charger", "station", "ev-charger", "charger", "fast", "electric", "vehicle")
        ),
        IconEntry(
            key = "lucide:eye-closed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eye_closed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Eye Closed",
            labelEn = "Eye Closed",
            tags = listOf("mask", "hidden", "hide", "eye-closed", "eye closed", "conceal")
        ),
        IconEntry(
            key = "lucide:eye-dashed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eye_dashed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Eye Dashed",
            labelEn = "Eye Dashed",
            tags = listOf("eye-dashed", "invisible", "eye dashed", "mask", "hidden", "hide", "conceal")
        ),
        IconEntry(
            key = "lucide:eye-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_eye_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Eye Off",
            labelEn = "Eye Off",
            tags = listOf("eye-off", "conceal", "eye off", "mask")
        ),
        IconEntry(
            key = "lucide:face-angry",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_face_angry,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Face Angry",
            labelEn = "Face Angry",
            tags = listOf("resentment", "anger", "face angry", "fury", "irritation", "rage", "face-angry")
        ),
        IconEntry(
            key = "lucide:face-expressionless",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_face_expressionless,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Face Expressionless",
            labelEn = "Face Expressionless",
            tags = listOf("annoyance", "annoyed", "frustration", "face expressionless", "face-expressionless", "nuisance", "emoji")
        ),
        IconEntry(
            key = "lucide:face-grinning",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_face_grinning,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Face Grinning",
            labelEn = "Face Grinning",
            tags = listOf("happiness", "good", "happy", "laugh", "broad smile", "face-grinning", "face grinning")
        ),
        IconEntry(
            key = "lucide:face-neutral",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_face_neutral,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Face Neutral",
            labelEn = "Face Neutral",
            tags = listOf("face neutral", "neutral", "emotion", "meh", "blank stare", "emoji", "face-neutral")
        ),
        IconEntry(
            key = "lucide:face-slightly-frowning",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_face_slightly_frowning,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Face Slightly Frowning",
            labelEn = "Face Slightly Frowning",
            tags = listOf("face slightly frowning", "face-slightly-frowning", "concern", "emotion", "bad", "sadness", "emoji")
        ),
        IconEntry(
            key = "lucide:factory",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_factory,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Factory",
            labelEn = "Factory",
            tags = listOf("factory", "building", "industry", "business", "energy", "manufacture")
        ),
        IconEntry(
            key = "lucide:fan",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fan,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Fan",
            labelEn = "Fan",
            tags = listOf("ventilation", "ventilator", "blower", "air", "cooler", "fan")
        ),
        IconEntry(
            key = "lucide:faucet",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_faucet,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Faucet",
            labelEn = "Faucet",
            tags = listOf("drinking", "faucet", "spigot", "water", "tap", "fresh")
        ),
        IconEntry(
            key = "lucide:fence",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fence,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Fence",
            labelEn = "Fence",
            tags = listOf("diy", "picket", "woodwork", "panels", "fence", "materials")
        ),
        IconEntry(
            key = "lucide:ferris-wheel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ferris_wheel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Ferris Wheel",
            labelEn = "Ferris Wheel",
            tags = listOf("ferris-wheel", "entertainment", "attraction", "ferris wheel", "big wheel", "daisy wheel", "observation")
        ),
        IconEntry(
            key = "lucide:file-archive",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_archive,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Archive",
            labelEn = "File Archive",
            tags = listOf("archive", "zip", "file archive", "file-archive", "package")
        ),
        IconEntry(
            key = "lucide:file-axis-3d",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_axis_3d,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Axis 3D",
            labelEn = "File Axis 3D",
            tags = listOf("axis", "file-axis-3d", "file axis 3d", "3d")
        ),
        IconEntry(
            key = "lucide:file-badge",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_badge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Badge",
            labelEn = "File Badge",
            tags = listOf("achievement", "award", "rosette", "file-badge", "file badge", "badge", "prize")
        ),
        IconEntry(
            key = "lucide:file-box",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_box,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Box",
            labelEn = "File Box",
            tags = listOf("file-box", "sheet", "file box", "box", "3d", "cube", "page")
        ),
        IconEntry(
            key = "lucide:file-braces-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_braces_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Braces Corner",
            labelEn = "File Braces Corner",
            tags = listOf("file braces corner", "file-braces-corner")
        ),
        IconEntry(
            key = "lucide:file-braces",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_braces,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Braces",
            labelEn = "File Braces",
            tags = listOf("file braces", "file-braces")
        ),
        IconEntry(
            key = "lucide:file-chart-column-increasing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_chart_column_increasing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Chart Column Increasing",
            labelEn = "File Chart Column Increasing",
            tags = listOf("trending up", "file-chart-column-increasing", "file chart column increasing")
        ),
        IconEntry(
            key = "lucide:file-chart-column",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_chart_column,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Chart Column",
            labelEn = "File Chart Column",
            tags = listOf("file chart column", "file-chart-column")
        ),
        IconEntry(
            key = "lucide:file-chart-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_chart_line,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Chart Line",
            labelEn = "File Chart Line",
            tags = listOf("file chart line", "file-chart-line")
        ),
        IconEntry(
            key = "lucide:file-chart-pie",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_chart_pie,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Chart Pie",
            labelEn = "File Chart Pie",
            tags = listOf("file chart pie", "file-chart-pie")
        ),
        IconEntry(
            key = "lucide:file-check-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_check_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Check Corner",
            labelEn = "File Check Corner",
            tags = listOf("file-check-corner", "file check corner")
        ),
        IconEntry(
            key = "lucide:file-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Check",
            labelEn = "File Check",
            tags = listOf("file-check", "file check")
        ),
        IconEntry(
            key = "lucide:file-clock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Clock",
            labelEn = "File Clock",
            tags = listOf("file clock", "file-clock", "log")
        ),
        IconEntry(
            key = "lucide:file-code-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_code_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Code Corner",
            labelEn = "File Code Corner",
            tags = listOf("file code corner", "file-code-corner")
        ),
        IconEntry(
            key = "lucide:file-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Cog",
            labelEn = "File Cog",
            tags = listOf("file cog", "executable", "file-cog")
        ),
        IconEntry(
            key = "lucide:file-database",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_database,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Database",
            labelEn = "File Database",
            tags = listOf("file-database", "file database")
        ),
        IconEntry(
            key = "lucide:file-diff",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_diff,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Diff",
            labelEn = "File Diff",
            tags = listOf("patch", "diff", "file diff", "file-diff")
        ),
        IconEntry(
            key = "lucide:file-digit",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_digit,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Digit",
            labelEn = "File Digit",
            tags = listOf("file digit", "file-digit")
        ),
        IconEntry(
            key = "lucide:file-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Down",
            labelEn = "File Down",
            tags = listOf("file-down", "file down", "download")
        ),
        IconEntry(
            key = "lucide:file-exclamation-point",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_exclamation_point,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Exclamation Point",
            labelEn = "File Exclamation Point",
            tags = listOf("file exclamation point", "protected", "file-exclamation-point", "hidden")
        ),
        IconEntry(
            key = "lucide:file-headphone",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_headphone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Headphone",
            labelEn = "File Headphone",
            tags = listOf("headphones", "file headphone", "file-headphone")
        ),
        IconEntry(
            key = "lucide:file-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Heart",
            labelEn = "File Heart",
            tags = listOf("bookmark", "file-heart", "file heart", "quick link")
        ),
        IconEntry(
            key = "lucide:file-image",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_image,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Image",
            labelEn = "File Image",
            tags = listOf("file image", "image", "graphics", "file-image", "picture", "photo")
        ),
        IconEntry(
            key = "lucide:file-input",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_input,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Input",
            labelEn = "File Input",
            tags = listOf("file-input", "file input")
        ),
        IconEntry(
            key = "lucide:file-key",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_key,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Key",
            labelEn = "File Key",
            tags = listOf("file key", "file-key")
        ),
        IconEntry(
            key = "lucide:file-lock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_lock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Lock",
            labelEn = "File Lock",
            tags = listOf("file lock", "file-lock")
        ),
        IconEntry(
            key = "lucide:file-minus-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_minus_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Minus Corner",
            labelEn = "File Minus Corner",
            tags = listOf("file minus corner", "file-minus-corner")
        ),
        IconEntry(
            key = "lucide:file-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Minus",
            labelEn = "File Minus",
            tags = listOf("file minus", "file-minus", "discard")
        ),
        IconEntry(
            key = "lucide:file-music",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_music,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Music",
            labelEn = "File Music",
            tags = listOf("sound", "track", "noise", "file-music", "digital", "audio", "file music")
        ),
        IconEntry(
            key = "lucide:file-output",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_output,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Output",
            labelEn = "File Output",
            tags = listOf("file-output", "file output")
        ),
        IconEntry(
            key = "lucide:file-pen-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_pen_line,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Pen Line",
            labelEn = "File Pen Line",
            tags = listOf("file-pen-line", "file pen line")
        ),
        IconEntry(
            key = "lucide:file-pen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_pen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Pen",
            labelEn = "File Pen",
            tags = listOf("file pen", "signature", "file-pen")
        ),
        IconEntry(
            key = "lucide:file-play",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_play,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Play",
            labelEn = "File Play",
            tags = listOf("film", "video", "file play", "file-play", "movie")
        ),
        IconEntry(
            key = "lucide:file-plus-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_plus_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Plus Corner",
            labelEn = "File Plus Corner",
            tags = listOf("file plus corner", "file-plus-corner")
        ),
        IconEntry(
            key = "lucide:file-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Plus",
            labelEn = "File Plus",
            tags = listOf("file-plus", "file plus")
        ),
        IconEntry(
            key = "lucide:file-question-mark",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_question_mark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Question Mark",
            labelEn = "File Question Mark",
            tags = listOf("file-question-mark", "readme", "file question mark")
        ),
        IconEntry(
            key = "lucide:file-scan",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_scan,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Scan",
            labelEn = "File Scan",
            tags = listOf("file scan", "file-scan", "code", "scan")
        ),
        IconEntry(
            key = "lucide:file-search-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_search_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Search Corner",
            labelEn = "File Search Corner",
            tags = listOf("file search corner", "file-search-corner")
        ),
        IconEntry(
            key = "lucide:file-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_search,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Search",
            labelEn = "File Search",
            tags = listOf("file-search", "lost", "file search", "browser")
        ),
        IconEntry(
            key = "lucide:file-signal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_signal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Signal",
            labelEn = "File Signal",
            tags = listOf("music", "volume", "file-signal", "file signal", "audio")
        ),
        IconEntry(
            key = "lucide:file-sliders",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_sliders,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Sliders",
            labelEn = "File Sliders",
            tags = listOf("cogged", "machinery", "mechanical", "file sliders", "file-sliders", "gear", "configuration")
        ),
        IconEntry(
            key = "lucide:file-spreadsheet",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_spreadsheet,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Spreadsheet",
            labelEn = "File Spreadsheet",
            tags = listOf("table", "file spreadsheet", "sheet", "spreadsheet", "file-spreadsheet")
        ),
        IconEntry(
            key = "lucide:file-stack",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_stack,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Stack",
            labelEn = "File Stack",
            tags = listOf("file stack", "version control", "history", "revisions", "file-stack", "versions")
        ),
        IconEntry(
            key = "lucide:file-symlink",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_symlink,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Symlink",
            labelEn = "File Symlink",
            tags = listOf("symbolic", "symlink", "file symlink", "file-symlink", "link")
        ),
        IconEntry(
            key = "lucide:file-terminal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_terminal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "File Terminal",
            labelEn = "File Terminal",
            tags = listOf("file-terminal", "script", "executable", "file terminal", "terminal", "bash")
        ),
        IconEntry(
            key = "lucide:file-type-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_type_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Type Corner",
            labelEn = "File Type Corner",
            tags = listOf("file type corner", "file-type-corner")
        ),
        IconEntry(
            key = "lucide:file-type",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_type,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Type",
            labelEn = "File Type",
            tags = listOf("type", "file type", "file-type")
        ),
        IconEntry(
            key = "lucide:file-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File Up",
            labelEn = "File Up",
            tags = listOf("file up", "upload", "export", "file-up", "import")
        ),
        IconEntry(
            key = "lucide:file-user",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_user,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "File User",
            labelEn = "File User",
            tags = listOf("file-user", "resume", "personal information", "file user", "listing", "networking", "cover letter")
        ),
        IconEntry(
            key = "lucide:file-video-camera",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_video_camera,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Video Camera",
            labelEn = "File Video Camera",
            tags = listOf("file video camera", "video", "file-video-camera")
        ),
        IconEntry(
            key = "lucide:file-volume",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_volume,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "File Volume",
            labelEn = "File Volume",
            tags = listOf("audio", "file volume", "file-volume", "volume")
        ),
    )

    private fun getPart8(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:file-x-corner",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_x_corner,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File X Corner",
            labelEn = "File X Corner",
            tags = listOf("file-x-corner", "file x corner")
        ),
        IconEntry(
            key = "lucide:file-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File X",
            labelEn = "File X",
            tags = listOf("lost", "file-x", "file x")
        ),
        IconEntry(
            key = "lucide:file",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_file,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "File",
            labelEn = "File",
            tags = listOf("file", "document")
        ),
        IconEntry(
            key = "lucide:files",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_files,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Files",
            labelEn = "Files",
            tags = listOf("multiple", "files", "documents", "copy")
        ),
        IconEntry(
            key = "lucide:fingerprint-pattern",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fingerprint_pattern,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Fingerprint Pattern",
            labelEn = "Fingerprint Pattern",
            tags = listOf("fingerprint-pattern", "biometric", "fingerprint pattern", "2fa", "security", "identity", "authentication")
        ),
        IconEntry(
            key = "lucide:fire-extinguisher",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fire_extinguisher,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Fire Extinguisher",
            labelEn = "Fire Extinguisher",
            tags = listOf("flames", "fire-extinguisher", "water", "smoke", "fire extinguisher", "foam", "spray")
        ),
        IconEntry(
            key = "lucide:fish-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fish_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Fish Off",
            labelEn = "Fish Off",
            tags = listOf("animal", "food", "intolerance", "diet", "fish off", "fish-off", "allergy")
        ),
        IconEntry(
            key = "lucide:fish-symbol",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fish_symbol,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Fish Symbol",
            labelEn = "Fish Symbol",
            tags = listOf("fish symbol", "fish-symbol")
        ),
        IconEntry(
            key = "lucide:fish",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fish,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Fish",
            labelEn = "Fish",
            tags = listOf("restaurant", "fish", "meal", "course", "seafood", "dish")
        ),
        IconEntry(
            key = "lucide:fishing-hook",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fishing_hook,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Fishing Hook",
            labelEn = "Fishing Hook",
            tags = listOf("reel", "sea", "bait", "boating", "fishing-hook", "angler", "fishing hook")
        ),
        IconEntry(
            key = "lucide:fishing-rod",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fishing_rod,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Fishing Rod",
            labelEn = "Fishing Rod",
            tags = listOf("reel", "rod", "hobby", "fishing-rod", "equipment", "fishing", "fishing rod")
        ),
        IconEntry(
            key = "lucide:flag-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flag_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Flag Off",
            labelEn = "Flag Off",
            tags = listOf("unmark", "unflag", "flag-off", "flag off")
        ),
        IconEntry(
            key = "lucide:flag-triangle-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flag_triangle_left,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Flag Triangle Left",
            labelEn = "Flag Triangle Left",
            tags = listOf("timeline", "pin", "flag-triangle-left", "marker", "report", "flag triangle left")
        ),
        IconEntry(
            key = "lucide:flag-triangle-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flag_triangle_right,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Flag Triangle Right",
            labelEn = "Flag Triangle Right",
            tags = listOf("timeline", "flag triangle right", "pin", "flag-triangle-right", "marker", "report")
        ),
        IconEntry(
            key = "lucide:flame-kindling",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flame_kindling,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Flame Kindling",
            labelEn = "Flame Kindling",
            tags = listOf("campfire", "wilderness", "lit", "flame-kindling", "camping", "outdoors", "flame kindling")
        ),
        IconEntry(
            key = "lucide:flashlight-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flashlight_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Flashlight Off",
            labelEn = "Flashlight Off",
            tags = listOf("flashlight-off", "flashlight off")
        ),
        IconEntry(
            key = "lucide:flashlight",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flashlight,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Flashlight",
            labelEn = "Flashlight",
            tags = listOf("beam", "safety", "torch", "flashlight", "light", "emergency")
        ),
        IconEntry(
            key = "lucide:flask-conical-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flask_conical_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Flask Conical Off",
            labelEn = "Flask Conical Off",
            tags = listOf("non toxic", "flask conical off", "flask-conical-off")
        ),
        IconEntry(
            key = "lucide:flask-round",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flask_round,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Flask Round",
            labelEn = "Flask Round",
            tags = listOf("test", "beaker", "chemistry", "experiment", "flask-round", "lab", "flask round")
        ),
        IconEntry(
            key = "lucide:flower",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_flower,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Flower",
            labelEn = "Flower",
            tags = listOf("spring", "sustainability", "plant", "nature", "flower")
        ),
        IconEntry(
            key = "lucide:folder-archive",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_archive,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Archive",
            labelEn = "Folder Archive",
            tags = listOf("archive", "folder-archive", "zip", "folder archive", "package")
        ),
        IconEntry(
            key = "lucide:folder-bookmark",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_bookmark,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Bookmark",
            labelEn = "Folder Bookmark",
            tags = listOf("bookmark", "folder-bookmark", "storage", "mark", "folder bookmark", "file", "folder")
        ),
        IconEntry(
            key = "lucide:folder-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Check",
            labelEn = "Folder Check",
            tags = listOf("folder check", "folder-check")
        ),
        IconEntry(
            key = "lucide:folder-clock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Clock",
            labelEn = "Folder Clock",
            tags = listOf("folder-clock", "folder clock")
        ),
        IconEntry(
            key = "lucide:folder-closed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_closed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Closed",
            labelEn = "Folder Closed",
            tags = listOf("closed", "folder closed", "folder-closed")
        ),
        IconEntry(
            key = "lucide:folder-code",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_code,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Folder Code",
            labelEn = "Folder Code",
            tags = listOf("develop", "folder-code", "folder code", "software")
        ),
        IconEntry(
            key = "lucide:folder-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Cog",
            labelEn = "Folder Cog",
            tags = listOf("control", "folder cog", "folder-cog")
        ),
        IconEntry(
            key = "lucide:folder-dot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_dot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Folder Dot",
            labelEn = "Folder Dot",
            tags = listOf("cogged", "folder dot", "project", "root", "gear", "folder-dot", "pinned")
        ),
        IconEntry(
            key = "lucide:folder-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Down",
            labelEn = "Folder Down",
            tags = listOf("folder-down", "export", "folder down", "download", "import")
        ),
        IconEntry(
            key = "lucide:folder-git",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_git,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Folder Git",
            labelEn = "Folder Git",
            tags = listOf("folder-git", "project", "repo", "git", "folder git", "root")
        ),
        IconEntry(
            key = "lucide:folder-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Heart",
            labelEn = "Folder Heart",
            tags = listOf("folder-heart", "bookmark", "folder heart", "quick link")
        ),
        IconEntry(
            key = "lucide:folder-input",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_input,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Input",
            labelEn = "Folder Input",
            tags = listOf("folder-input", "import", "export", "folder input")
        ),
        IconEntry(
            key = "lucide:folder-kanban",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_kanban,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Folder Kanban",
            labelEn = "Folder Kanban",
            tags = listOf("manage", "tickets", "overview", "folder kanban", "folder-kanban", "board", "projects")
        ),
        IconEntry(
            key = "lucide:folder-key",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_key,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Key",
            labelEn = "Folder Key",
            tags = listOf("folder key", "folder-key")
        ),
        IconEntry(
            key = "lucide:folder-lock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_lock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Lock",
            labelEn = "Folder Lock",
            tags = listOf("folder lock", "folder-lock")
        ),
        IconEntry(
            key = "lucide:folder-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Minus",
            labelEn = "Folder Minus",
            tags = listOf("folder minus", "folder-minus")
        ),
        IconEntry(
            key = "lucide:folder-open-dot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_open_dot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Folder Open Dot",
            labelEn = "Folder Open Dot",
            tags = listOf("project", "folder open dot", "folder-open-dot", "root", "pinned")
        ),
        IconEntry(
            key = "lucide:folder-output",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_output,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Output",
            labelEn = "Folder Output",
            tags = listOf("folder output", "import", "export", "folder-output")
        ),
        IconEntry(
            key = "lucide:folder-pen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_pen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Pen",
            labelEn = "Folder Pen",
            tags = listOf("folder-pen", "folder pen")
        ),
        IconEntry(
            key = "lucide:folder-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Plus",
            labelEn = "Folder Plus",
            tags = listOf("folder-plus", "folder plus")
        ),
        IconEntry(
            key = "lucide:folder-root",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_root,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Folder Root",
            labelEn = "Folder Root",
            tags = listOf("folder-root", "project", "repo", "git", "root", "folder root")
        ),
        IconEntry(
            key = "lucide:folder-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_search,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Search",
            labelEn = "Folder Search",
            tags = listOf("lost", "folder-search", "browser", "folder search")
        ),
        IconEntry(
            key = "lucide:folder-symlink",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_symlink,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Symlink",
            labelEn = "Folder Symlink",
            tags = listOf("symbolic", "symlink", "folder symlink", "folder-symlink", "link")
        ),
        IconEntry(
            key = "lucide:folder-sync",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_sync,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Sync",
            labelEn = "Folder Sync",
            tags = listOf("folder-sync", "transfer", "backup", "folder sync")
        ),
        IconEntry(
            key = "lucide:folder-tree",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_tree,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Tree",
            labelEn = "Folder Tree",
            tags = listOf("tree", "folder-tree", "folder tree", "browser")
        ),
        IconEntry(
            key = "lucide:folder-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder Up",
            labelEn = "Folder Up",
            tags = listOf("upload", "export", "import", "folder-up", "folder up")
        ),
        IconEntry(
            key = "lucide:folder-user",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_user,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Folder User",
            labelEn = "Folder User",
            tags = listOf("folder user", "document", "shared", "owner", "home folder", "access", "folder-user")
        ),
        IconEntry(
            key = "lucide:folder-users",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_users,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Folder Users",
            labelEn = "Folder Users",
            tags = listOf("group", "team", "permission", "document", "shared", "folder-users", "folder users")
        ),
        IconEntry(
            key = "lucide:folder-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folder_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folder X",
            labelEn = "Folder X",
            tags = listOf("folder x", "folder-x")
        ),
        IconEntry(
            key = "lucide:folders",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_folders,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Folders",
            labelEn = "Folders",
            tags = listOf("multiple", "directories", "copy", "folders")
        ),
        IconEntry(
            key = "lucide:forklift",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_forklift,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Forklift",
            labelEn = "Forklift",
            tags = listOf("machinery", "forklift", "lifting", "storage", "industrial", "warehouse")
        ),
        IconEntry(
            key = "lucide:form",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_form,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Form",
            labelEn = "Form",
            tags = listOf("form", "document", "layout", "file", "paper", "page")
        ),
        IconEntry(
            key = "lucide:forward",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_forward,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Forward",
            labelEn = "Forward",
            tags = listOf("share", "email", "forward", "send")
        ),
        IconEntry(
            key = "lucide:frame",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_frame,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Frame",
            labelEn = "Frame",
            tags = listOf("tool", "logo", "design", "frame")
        ),
        IconEntry(
            key = "lucide:fuel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fuel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Fuel",
            labelEn = "Fuel",
            tags = listOf("filling-station", "fuel", "tank", "petrol", "gas")
        ),
        IconEntry(
            key = "lucide:fullscreen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_fullscreen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Fullscreen",
            labelEn = "Fullscreen",
            tags = listOf("zoom", "fullscreen", "focus", "preview", "camera", "expand")
        ),
        IconEntry(
            key = "lucide:funnel-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_funnel_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Funnel Plus",
            labelEn = "Funnel Plus",
            tags = listOf("funnel-plus", "funnel plus")
        ),
        IconEntry(
            key = "lucide:funnel-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_funnel_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Funnel X",
            labelEn = "Funnel X",
            tags = listOf("funnel x", "funnel-x")
        ),
        IconEntry(
            key = "lucide:funnel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_funnel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Funnel",
            labelEn = "Funnel",
            tags = listOf("hopper", "funnel", "filter")
        ),
        IconEntry(
            key = "lucide:galaxy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_galaxy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Galaxy",
            labelEn = "Galaxy",
            tags = listOf("galaxy", "astronomy", "space", "cosmos", "universe", "stars")
        ),
        IconEntry(
            key = "lucide:gallery-horizontal-end",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gallery_horizontal_end,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Gallery Horizontal End",
            labelEn = "Gallery Horizontal End",
            tags = listOf("backup", "history", "time machine", "versions", "gallery horizontal end", "gallery-horizontal-end")
        ),
        IconEntry(
            key = "lucide:gallery-horizontal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gallery_horizontal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Gallery Horizontal",
            labelEn = "Gallery Horizontal",
            tags = listOf("pictures", "gallery horizontal", "scroll", "images", "gallery-horizontal", "swipe", "carousel")
        ),
        IconEntry(
            key = "lucide:gallery-thumbnails",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gallery_thumbnails,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Gallery Thumbnails",
            labelEn = "Gallery Thumbnails",
            tags = listOf("pictures", "gallery thumbnails", "gallery-thumbnails", "images", "portfolio", "album", "carousel")
        ),
        IconEntry(
            key = "lucide:gallery-vertical-end",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gallery_vertical_end,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Gallery Vertical End",
            labelEn = "Gallery Vertical End",
            tags = listOf("backup", "gallery vertical end", "history", "time machine", "versions", "gallery-vertical-end")
        ),
        IconEntry(
            key = "lucide:gallery-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gallery_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Gallery Vertical",
            labelEn = "Gallery Vertical",
            tags = listOf("pictures", "gallery vertical", "scroll", "images", "gallery-vertical", "swipe", "carousel")
        ),
        IconEntry(
            key = "lucide:gamepad-directional",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gamepad_directional,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Gamepad Directional",
            labelEn = "Gamepad Directional",
            tags = listOf("arrow", "gamepad directional", "button", "gamepad-directional", "navigation", "direction", "controller")
        ),
        IconEntry(
            key = "lucide:gamepad",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gamepad,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Gamepad",
            labelEn = "Gamepad",
            tags = listOf("gamepad", "console")
        ),
        IconEntry(
            key = "lucide:gap-horizontal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gap_horizontal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Gap Horizontal",
            labelEn = "Gap Horizontal",
            tags = listOf("spacing", "gap", "padding", "gap horizontal", "space", "gap-horizontal", "between")
        ),
        IconEntry(
            key = "lucide:gap-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gap_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Gap Vertical",
            labelEn = "Gap Vertical",
            tags = listOf("spacing", "gap", "padding", "space", "gap vertical", "gap-vertical", "between")
        ),
        IconEntry(
            key = "lucide:gauge",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gauge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Gauge",
            labelEn = "Gauge",
            tags = listOf("pressure", "meter", "dial", "dashboard", "gauge", "speed")
        ),
        IconEntry(
            key = "lucide:gavel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gavel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Gavel",
            labelEn = "Gavel",
            tags = listOf("court", "judgment", "law", "legal", "justice", "gavel")
        ),
        IconEntry(
            key = "lucide:georgian-lari",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_georgian_lari,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Georgian Lari",
            labelEn = "Georgian Lari",
            tags = listOf("georgian-lari", "georgian lari")
        ),
        IconEntry(
            key = "lucide:germ-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_germ_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Germ Off",
            labelEn = "Germ Off",
            tags = listOf("germ-off", "germ off")
        ),
        IconEntry(
            key = "lucide:germ",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_germ,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Germ",
            labelEn = "Germ",
            tags = listOf("microbe", "germ", "bacterium", "bacteria", "virus")
        ),
        IconEntry(
            key = "lucide:ghost",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ghost,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Ghost",
            labelEn = "Ghost",
            tags = listOf("wraith", "spirit", "haunt", "apparition", "specter", "ghost")
        ),
        IconEntry(
            key = "lucide:git-branch-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_branch_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Branch Minus",
            labelEn = "Git Branch Minus",
            tags = listOf("git-branch-minus", "git branch minus")
        ),
        IconEntry(
            key = "lucide:git-branch-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_branch_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Branch Plus",
            labelEn = "Git Branch Plus",
            tags = listOf("git branch plus", "git-branch-plus")
        ),
        IconEntry(
            key = "lucide:git-commit-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_commit_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Commit Vertical",
            labelEn = "Git Commit Vertical",
            tags = listOf("station", "version control", "waypoint", "git commit vertical", "git-commit-vertical", "stop", "code")
        ),
        IconEntry(
            key = "lucide:git-compare-arrows",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_compare_arrows,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Compare Arrows",
            labelEn = "Git Compare Arrows",
            tags = listOf("git compare arrows", "git-compare-arrows")
        ),
        IconEntry(
            key = "lucide:git-compare",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_compare,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Compare",
            labelEn = "Git Compare",
            tags = listOf("git-compare", "version control", "diff", "git compare", "code")
        ),
        IconEntry(
            key = "lucide:git-fork",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_fork,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Fork",
            labelEn = "Git Fork",
            tags = listOf("git fork", "version control", "code", "git-fork")
        ),
        IconEntry(
            key = "lucide:git-graph",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_graph,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Graph",
            labelEn = "Git Graph",
            tags = listOf("version control", "git graph", "commits", "gitlens", "commit graph", "git-graph", "code")
        ),
        IconEntry(
            key = "lucide:git-merge-conflict",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_merge_conflict,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Merge Conflict",
            labelEn = "Git Merge Conflict",
            tags = listOf("conflict", "diff", "git-merge-conflict", "commits", "git merge conflict", "error")
        ),
        IconEntry(
            key = "lucide:git-merge",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_merge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Git Merge",
            labelEn = "Git Merge",
            tags = listOf("git-merge", "version control", "code", "git merge")
        ),
        IconEntry(
            key = "lucide:git-pull-request-arrow",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_pull_request_arrow,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Pull Request Arrow",
            labelEn = "Git Pull Request Arrow",
            tags = listOf("git pull request arrow", "git-pull-request-arrow")
        ),
        IconEntry(
            key = "lucide:git-pull-request-closed",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_pull_request_closed,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Pull Request Closed",
            labelEn = "Git Pull Request Closed",
            tags = listOf("git pull request closed", "rejected", "x", "cancelled", "closed", "git-pull-request-closed")
        ),
        IconEntry(
            key = "lucide:git-pull-request-create-arrow",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_pull_request_create_arrow,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Pull Request Create Arrow",
            labelEn = "Git Pull Request Create Arrow",
            tags = listOf("git-pull-request-create-arrow", "git pull request create arrow")
        ),
        IconEntry(
            key = "lucide:git-pull-request-create",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_pull_request_create,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Pull Request Create",
            labelEn = "Git Pull Request Create",
            tags = listOf("git pull request create", "git-pull-request-create")
        ),
        IconEntry(
            key = "lucide:git-pull-request-draft",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_git_pull_request_draft,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Git Pull Request Draft",
            labelEn = "Git Pull Request Draft",
            tags = listOf("git pull request draft", "dashed", "git-pull-request-draft", "draft")
        ),
        IconEntry(
            key = "lucide:globe-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_globe_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Globe Check",
            labelEn = "Globe Check",
            tags = listOf("available", "status", "internet", "web", "online", "globe-check", "globe check")
        ),
        IconEntry(
            key = "lucide:globe-code",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_globe_code,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Globe Code",
            labelEn = "Globe Code",
            tags = listOf("connection", "internet", "network", "globe", "globe code", "website", "globe-code")
        ),
        IconEntry(
            key = "lucide:globe-lock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_globe_lock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Globe Lock",
            labelEn = "Globe Lock",
            tags = listOf("connection", "globe-lock", "encryption", "network", "globe lock", "vpn")
        ),
        IconEntry(
            key = "lucide:globe-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_globe_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Globe Off",
            labelEn = "Globe Off",
            tags = listOf("globe-off", "globe off", "earth", "mute", "planet", "globe", "avoid")
        ),
        IconEntry(
            key = "lucide:globe-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_globe_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Globe X",
            labelEn = "Globe X",
            tags = listOf("internet", "offline", "globe x", "disconnected", "network", "globe", "globe-x")
        ),
        IconEntry(
            key = "lucide:goal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_goal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Goal",
            labelEn = "Goal",
            tags = listOf("bullseye", "flag", "goal")
        ),
        IconEntry(
            key = "lucide:gpu",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_gpu,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Gpu",
            labelEn = "Gpu",
            tags = listOf("gpu", "cores", "chip", "computer", "technology", "processor")
        ),
        IconEntry(
            key = "lucide:grape",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_grape,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Grape",
            labelEn = "Grape",
            tags = listOf("wine", "food", "fruit", "grape")
        ),
        IconEntry(
            key = "lucide:graph-hub",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_graph_hub,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Graph Hub",
            labelEn = "Graph Hub",
            tags = listOf("graph-hub", "branch", "parallel", "distribute", "load balancer", "graph hub", "traffic")
        ),
        IconEntry(
            key = "lucide:graph-star",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_graph_star,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Graph Star",
            labelEn = "Graph Star",
            tags = listOf("dependencies", "graph star", "mind map", "cluster", "graph-star", "bonds", "hub")
        ),
        IconEntry(
            key = "lucide:grip-horizontal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_grip_horizontal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Grip Horizontal",
            labelEn = "Grip Horizontal",
            tags = listOf("grip-horizontal", "grip horizontal")
        ),
    )

    private fun getPart9(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:grip-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_grip_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Grip Vertical",
            labelEn = "Grip Vertical",
            tags = listOf("grip vertical", "grip-vertical")
        ),
        IconEntry(
            key = "lucide:grip",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_grip,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Grip",
            labelEn = "Grip",
            tags = listOf("move", "grab", "drag", "handle", "grip", "dots")
        ),
        IconEntry(
            key = "lucide:groceries",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_groceries,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Groceries",
            labelEn = "Groceries",
            tags = listOf("groceries", "vegetables", "food", "market", "grocery", "health")
        ),
        IconEntry(
            key = "lucide:group",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_group,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Group",
            labelEn = "Group",
            tags = listOf("cubes", "group", "collection", "packages", "units", "parts")
        ),
        IconEntry(
            key = "lucide:guitar-electric",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_guitar_electric,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Guitar Electric",
            labelEn = "Guitar Electric",
            tags = listOf("guitar electric", "guitar-electric")
        ),
        IconEntry(
            key = "lucide:half-note",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_half_note,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Half Note",
            labelEn = "Half Note",
            tags = listOf("sound", "music", "half note", "crotchet", "half-note", "note", "audio")
        ),
        IconEntry(
            key = "lucide:ham",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ham,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Ham",
            labelEn = "Ham",
            tags = listOf("pig", "ham", "food", "bone", "meat", "pork")
        ),
        IconEntry(
            key = "lucide:hamburger",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hamburger,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Hamburger",
            labelEn = "Hamburger",
            tags = listOf("patty", "cheeseburger", "burger", "hamburger", "beef", "meat")
        ),
        IconEntry(
            key = "lucide:hand-coins",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_coins,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Hand Coins",
            labelEn = "Hand Coins",
            tags = listOf("savings", "hand-coins", "banking", "offers", "finance", "money", "hand coins")
        ),
        IconEntry(
            key = "lucide:hand-fist",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_fist,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Hand Fist",
            labelEn = "Hand Fist",
            tags = listOf("clench", "strength", "power", "unity", "hand fist", "hand-fist", "solidarity")
        ),
        IconEntry(
            key = "lucide:hand-grab",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_grab,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Hand Grab",
            labelEn = "Hand Grab",
            tags = listOf("hand-grab", "hand grab", "hand")
        ),
        IconEntry(
            key = "lucide:hand-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Hand Heart",
            labelEn = "Hand Heart",
            tags = listOf("hand-heart", "emotion", "hand heart")
        ),
        IconEntry(
            key = "lucide:hand-helping",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_helping,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Hand Helping",
            labelEn = "Hand Helping",
            tags = listOf("begging", "agreement", "hand-helping", "hand helping", "proposal", "help", "charity")
        ),
        IconEntry(
            key = "lucide:hand-metal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_metal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Hand Metal",
            labelEn = "Hand Metal",
            tags = listOf("rock", "hand metal", "hand-metal")
        ),
        IconEntry(
            key = "lucide:hand-platter",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand_platter,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Hand Platter",
            labelEn = "Hand Platter",
            tags = listOf("restaurant", "hand-platter", "table service", "hand platter", "served", "waiter", "waitress")
        ),
        IconEntry(
            key = "lucide:hand",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hand,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Hand",
            labelEn = "Hand",
            tags = listOf("move", "grab", "hand", "wave", "mouse")
        ),
        IconEntry(
            key = "lucide:handbag",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_handbag,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Handbag",
            labelEn = "Handbag",
            tags = listOf("baggage", "fashion", "bag", "handbag", "carry", "clutch")
        ),
        IconEntry(
            key = "lucide:handle-bottom-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_handle_bottom_right,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Handle Bottom Right",
            labelEn = "Handle Bottom Right",
            tags = listOf("move", "grab", "drag", "handle", "handle bottom right", "handle-bottom-right", "mouse")
        ),
        IconEntry(
            key = "lucide:hard-drive-download",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hard_drive_download,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Hard Drive Download",
            labelEn = "Hard Drive Download",
            tags = listOf("hard drive download", "hard-drive-download")
        ),
        IconEntry(
            key = "lucide:hard-drive-upload",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hard_drive_upload,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Hard Drive Upload",
            labelEn = "Hard Drive Upload",
            tags = listOf("hard-drive-upload", "hard drive upload", "save")
        ),
        IconEntry(
            key = "lucide:hard-hat",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hard_hat,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Hard Hat",
            labelEn = "Hard Hat",
            tags = listOf("helmet", "construction", "hard hat", "hard-hat", "safety")
        ),
        IconEntry(
            key = "lucide:hash",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hash,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Hash",
            labelEn = "Hash",
            tags = listOf("hashtag", "hash", "pound", "number")
        ),
        IconEntry(
            key = "lucide:hat-glasses",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hat_glasses,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Hat Glasses",
            labelEn = "Hat Glasses",
            tags = listOf("anonymous", "hat glasses", "costume", "masked", "disguise", "incognito", "hat-glasses")
        ),
        IconEntry(
            key = "lucide:haze",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_haze,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Haze",
            labelEn = "Haze",
            tags = listOf("mist", "haze", "fog")
        ),
        IconEntry(
            key = "lucide:hd",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hd,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Hd",
            labelEn = "Hd",
            tags = listOf("720p", "hd", "tv", "video", "high definition", "resolution")
        ),
        IconEntry(
            key = "lucide:hdmi-port",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hdmi_port,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Hdmi Port",
            labelEn = "Hdmi Port",
            tags = listOf("hdmi-port", "socket", "slot", "connector", "controller", "hdmi port", "plug")
        ),
        IconEntry(
            key = "lucide:heading-5",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heading_5,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Heading 5",
            labelEn = "Heading 5",
            tags = listOf("heading-5", "h5", "heading 5")
        ),
        IconEntry(
            key = "lucide:heading-6",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heading_6,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Heading 6",
            labelEn = "Heading 6",
            tags = listOf("heading 6", "heading-6", "h6")
        ),
        IconEntry(
            key = "lucide:headphone-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_headphone_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Headphone Off",
            labelEn = "Headphone Off",
            tags = listOf("sound", "music", "headphone-off", "mute", "headphone off", "audio")
        ),
        IconEntry(
            key = "lucide:headset",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_headset,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Headset",
            labelEn = "Headset",
            tags = listOf("headset", "sound", "music", "gaming", "headphones", "audio")
        ),
        IconEntry(
            key = "lucide:heart-crack",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heart_crack,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Heart Crack",
            labelEn = "Heart Crack",
            tags = listOf("heart-crack", "emotion", "heartbreak", "heart crack", "sadness")
        ),
        IconEntry(
            key = "lucide:heart-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heart_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Heart Minus",
            labelEn = "Heart Minus",
            tags = listOf("heart-minus", "unfavorite", "heart minus", "unlike", "damage")
        ),
        IconEntry(
            key = "lucide:heart-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heart_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Heart Off",
            labelEn = "Heart Off",
            tags = listOf("heart-off", "hate", "dislike", "emotion", "unlike", "heart off")
        ),
        IconEntry(
            key = "lucide:heart-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heart_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Heart Plus",
            labelEn = "Heart Plus",
            tags = listOf("heart plus", "support", "heart-plus")
        ),
        IconEntry(
            key = "lucide:heart-pulse",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heart_pulse,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Пульс / Сердце",
            labelEn = "Heart Pulse",
            tags = listOf("давление", "зож", "medical", "pulse", "кардио", "cardio", "health", "пульс", "сердце", "heart pulse", "heartbeat", "blood pressure", "heart-pulse")
        ),
        IconEntry(
            key = "lucide:heart-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heart_x,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Heart X",
            labelEn = "Heart X",
            tags = listOf("heart-x", "heart x", "unlike", "unfavorite")
        ),
        IconEntry(
            key = "lucide:heater",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_heater,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Heater",
            labelEn = "Heater",
            tags = listOf("comfort", "warmth", "fire", "heater", "stove", "heating")
        ),
        IconEntry(
            key = "lucide:helicopter",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_helicopter,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Helicopter",
            labelEn = "Helicopter",
            tags = listOf("aviation", "helipad", "helicopter", "transport", "rotor", "flying")
        ),
        IconEntry(
            key = "lucide:hexagon",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hexagon,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Hexagon",
            labelEn = "Hexagon",
            tags = listOf("hexagon", "node.js", "logo", "shape")
        ),
        IconEntry(
            key = "lucide:highlighter",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_highlighter,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Highlighter",
            labelEn = "Highlighter",
            tags = listOf("mark", "highlighter", "text")
        ),
        IconEntry(
            key = "lucide:hiking-stick",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hiking_stick,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Hiking Stick",
            labelEn = "Hiking Stick",
            tags = listOf("trekking", "hiking", "hiking stick", "stick", "hiking-stick", "walking", "trail")
        ),
        IconEntry(
            key = "lucide:hockey-stick",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hockey_stick,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.SPORT,
            labelRu = "Hockey Stick",
            labelEn = "Hockey Stick",
            tags = listOf("athlete", "sport", "hockey stick", "ice", "hockey-stick", "puck", "campus")
        ),
        IconEntry(
            key = "lucide:hop-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hop_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Hop Off",
            labelEn = "Hop Off",
            tags = listOf("hop free", "hop off", "intolerance", "diet", "hop-off", "allergy")
        ),
        IconEntry(
            key = "lucide:hop",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hop,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Hop",
            labelEn = "Hop",
            tags = listOf("hop", "drink", "brewery", "beer")
        ),
        IconEntry(
            key = "lucide:hospital",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hospital,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Hospital",
            labelEn = "Hospital",
            tags = listOf("sanatorium", "hospital", "doctor", "healthcare", "hospice", "infirmary")
        ),
        IconEntry(
            key = "lucide:hot-air-balloon",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hot_air_balloon,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Hot Air Balloon",
            labelEn = "Hot Air Balloon",
            tags = listOf("hot-air-balloon", "travel", "hot air balloon", "aircraft", "flight", "adventure", "sky")
        ),
        IconEntry(
            key = "lucide:hotel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hotel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Hotel",
            labelEn = "Hotel",
            tags = listOf("hotel", "motel", "building", "inn", "hostel")
        ),
        IconEntry(
            key = "lucide:hourglass-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_hourglass_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Hourglass Cog",
            labelEn = "Hourglass Cog",
            tags = listOf("countdown", "hourglass cog", "hourglass-cog", "timeout", "expiration")
        ),
        IconEntry(
            key = "lucide:house-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_house_cog,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "House Cog",
            labelEn = "House Cog",
            tags = listOf("property", "automation", "house cog", "house-cog")
        ),
        IconEntry(
            key = "lucide:house-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_house_heart,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "House Heart",
            labelEn = "House Heart",
            tags = listOf("home sweet home", "abode", "healthy living", "house-heart", "lifestyle", "house heart")
        ),
        IconEntry(
            key = "lucide:house-plug",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_house_plug,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "House Plug",
            labelEn = "House Plug",
            tags = listOf("autarky", "energy", "house plug", "house-plug")
        ),
        IconEntry(
            key = "lucide:house-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_house_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "House Plus",
            labelEn = "House Plus",
            tags = listOf("house plus", "house-plus", "medical")
        ),
        IconEntry(
            key = "lucide:house-wifi",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_house_wifi,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "House Wifi",
            labelEn = "House Wifi",
            tags = listOf("connectivity", "house wifi", "house-wifi")
        ),
        IconEntry(
            key = "lucide:houses",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_houses,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Houses",
            labelEn = "Houses",
            tags = listOf("living", "homes", "buildings", "houses", "housing", "estate")
        ),
        IconEntry(
            key = "lucide:ice-cream-bowl",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ice_cream_bowl,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Ice Cream Bowl",
            labelEn = "Ice Cream Bowl",
            tags = listOf("restaurant", "dessert", "food", "gelato", "ice-cream-bowl", "ice cream bowl", "dish")
        ),
        IconEntry(
            key = "lucide:ice-cream-cone",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ice_cream_cone,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Ice Cream Cone",
            labelEn = "Ice Cream Cone",
            tags = listOf("food", "gelato", "ice cream cone", "ice-cream-cone")
        ),
        IconEntry(
            key = "lucide:id-card-lanyard",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_id_card_lanyard,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Id Card Lanyard",
            labelEn = "Id Card Lanyard",
            tags = listOf("id-card-lanyard", "id-card", "gate-pass", "id card lanyard", "employee")
        ),
        IconEntry(
            key = "lucide:id-card",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_id_card,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Id Card",
            labelEn = "Id Card",
            tags = listOf("card", "id card", "id-card", "secure", "badge", "identity", "authentication")
        ),
        IconEntry(
            key = "lucide:image-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Image Down",
            labelEn = "Image Down",
            tags = listOf("export", "image down", "save", "download", "image-down")
        ),
        IconEntry(
            key = "lucide:image-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Image Minus",
            labelEn = "Image Minus",
            tags = listOf("image-minus", "image minus")
        ),
        IconEntry(
            key = "lucide:image-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Image Off",
            labelEn = "Image Off",
            tags = listOf("image-off", "image off")
        ),
        IconEntry(
            key = "lucide:image-play",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_play,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Image Play",
            labelEn = "Image Play",
            tags = listOf("image play", "image-play", "gif")
        ),
        IconEntry(
            key = "lucide:image-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Image Plus",
            labelEn = "Image Plus",
            tags = listOf("image-plus", "image plus")
        ),
        IconEntry(
            key = "lucide:image-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Image Up",
            labelEn = "Image Up",
            tags = listOf("upload", "import", "image-up", "image up")
        ),
        IconEntry(
            key = "lucide:image-upscale",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image_upscale,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Image Upscale",
            labelEn = "Image Upscale",
            tags = listOf("increase", "resize", "image upscale", "image-upscale", "sharpen")
        ),
        IconEntry(
            key = "lucide:image",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_image,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Image",
            labelEn = "Image",
            tags = listOf("picture", "image", "photo")
        ),
        IconEntry(
            key = "lucide:images",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_images,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Images",
            labelEn = "Images",
            tags = listOf("gallery", "images", "multiple", "picture", "photo", "copy")
        ),
        IconEntry(
            key = "lucide:inbox",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_inbox,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Inbox",
            labelEn = "Inbox",
            tags = listOf("inbox", "email")
        ),
        IconEntry(
            key = "lucide:indian-rupee",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_indian_rupee,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Indian Rupee",
            labelEn = "Indian Rupee",
            tags = listOf("indian rupee", "indian-rupee")
        ),
        IconEntry(
            key = "lucide:infinity",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_infinity,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Infinity",
            labelEn = "Infinity",
            tags = listOf("infinity", "math", "forever", "unlimited", "loop")
        ),
        IconEntry(
            key = "lucide:info",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_info,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Info",
            labelEn = "Info",
            tags = listOf("advice", "info", "about", "help", "clue", "details")
        ),
        IconEntry(
            key = "lucide:inspection-panel",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_inspection_panel,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Inspection Panel",
            labelEn = "Inspection Panel",
            tags = listOf("inspection-panel", "tile", "cover", "inspection panel", "access", "materials", "metal")
        ),
        IconEntry(
            key = "lucide:iv-bag",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_iv_bag,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Iv Bag",
            labelEn = "Iv Bag",
            tags = listOf("infusion", "medication", "intravenous", "medicine", "iv bag", "iv-bag", "therapy")
        ),
        IconEntry(
            key = "lucide:japanese-yen",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_japanese_yen,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Japanese Yen",
            labelEn = "Japanese Yen",
            tags = listOf("japanese yen", "japanese-yen")
        ),
        IconEntry(
            key = "lucide:joystick",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_joystick,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOBBIES,
            labelRu = "Joystick",
            labelEn = "Joystick",
            tags = listOf("game", "console", "joystick", "control stick")
        ),
        IconEntry(
            key = "lucide:kanban",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_kanban,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Kanban",
            labelEn = "Kanban",
            tags = listOf("manage", "tickets", "overview", "board", "projects", "kanban")
        ),
        IconEntry(
            key = "lucide:kayak",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_kayak,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Kayak",
            labelEn = "Kayak",
            tags = listOf("kayak", "boat", "sport", "water", "paddle")
        ),
        IconEntry(
            key = "lucide:kazakh-tenge",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_kazakh_tenge,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FINANCE,
            labelRu = "Kazakh Tenge",
            labelEn = "Kazakh Tenge",
            tags = listOf("kazakh tenge", "kazakhstan", "kazakh-tenge")
        ),
        IconEntry(
            key = "lucide:key-round",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_key_round,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Key Round",
            labelEn = "Key Round",
            tags = listOf("key round", "key-round")
        ),
        IconEntry(
            key = "lucide:key-square",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_key_square,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Key Square",
            labelEn = "Key Square",
            tags = listOf("car key", "key square", "key-square")
        ),
        IconEntry(
            key = "lucide:keyboard-music",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_keyboard_music,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Keyboard Music",
            labelEn = "Keyboard Music",
            tags = listOf("sound", "music", "noise", "keyboard-music", "notes", "keyboard music", "audio")
        ),
        IconEntry(
            key = "lucide:keyboard-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_keyboard_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Keyboard Off",
            labelEn = "Keyboard Off",
            tags = listOf("unkeys", "keyboard off", "keyboard-off")
        ),
        IconEntry(
            key = "lucide:keyboard",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_keyboard,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Клавиатура / Печать",
            labelEn = "Keyboard",
            tags = listOf("текст", "слепая печать", "settings", "spell", "печать", "layout", "keyboard", "mouse", "клавиатура")
        ),
        IconEntry(
            key = "lucide:lamp-ceiling",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lamp_ceiling,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lamp Ceiling",
            labelEn = "Lamp Ceiling",
            tags = listOf("lamp-ceiling", "lamp ceiling")
        ),
        IconEntry(
            key = "lucide:lamp-desk",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lamp_desk,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lamp Desk",
            labelEn = "Lamp Desk",
            tags = listOf("lamp desk", "lamp-desk", "office", "desk")
        ),
        IconEntry(
            key = "lucide:lamp-floor",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lamp_floor,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lamp Floor",
            labelEn = "Lamp Floor",
            tags = listOf("floor", "lamp-floor", "lamp floor")
        ),
        IconEntry(
            key = "lucide:lamp-wall-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lamp_wall_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lamp Wall Down",
            labelEn = "Lamp Wall Down",
            tags = listOf("lamp-wall-down", "lamp wall down", "wall")
        ),
        IconEntry(
            key = "lucide:lamp-wall-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lamp_wall_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lamp Wall Up",
            labelEn = "Lamp Wall Up",
            tags = listOf("lamp-wall-up", "wall", "lamp wall up")
        ),
        IconEntry(
            key = "lucide:land-plot",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_land_plot,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.TRAVEL,
            labelRu = "Land Plot",
            labelEn = "Land Plot",
            tags = listOf("allotment", "land plot", "square metres", "parcel", "land-plot", "surface", "area")
        ),
        IconEntry(
            key = "lucide:laptop-minimal-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_laptop_minimal_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Laptop Minimal Check",
            labelEn = "Laptop Minimal Check",
            tags = listOf("laptop minimal check", "laptop-minimal-check")
        ),
        IconEntry(
            key = "lucide:laptop-minimal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_laptop_minimal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Laptop Minimal",
            labelEn = "Laptop Minimal",
            tags = listOf("laptop minimal", "laptop-minimal")
        ),
        IconEntry(
            key = "lucide:laser",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_laser,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Laser",
            labelEn = "Laser",
            tags = listOf("beam", "guide", "draw", "laser", "annotation", "highlight")
        ),
        IconEntry(
            key = "lucide:lasso-select",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lasso_select,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lasso Select",
            labelEn = "Lasso Select",
            tags = listOf("lasso select", "lasso-select")
        ),
        IconEntry(
            key = "lucide:lasso",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lasso,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Lasso",
            labelEn = "Lasso",
            tags = listOf("cursor", "lasso", "select")
        ),
        IconEntry(
            key = "lucide:layer-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layer_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layer Arrow Down",
            labelEn = "Layer Arrow Down",
            tags = listOf("lower", "backward", "move back", "layer arrow down", "step back", "layer-arrow-down", "demote")
        ),
        IconEntry(
            key = "lucide:layer-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layer_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layer Arrow Up",
            labelEn = "Layer Arrow Up",
            tags = listOf("layer-arrow-up", "layer arrow up", "move forward", "promote", "lift", "forward", "raise")
        ),
        IconEntry(
            key = "lucide:layers-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layers_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layers Arrow Down",
            labelEn = "Layers Arrow Down",
            tags = listOf("bottom", "send backward", "send to back", "layers arrow down", "layers-arrow-down", "background", "back")
        ),
        IconEntry(
            key = "lucide:layers-arrow-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layers_arrow_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layers Arrow Up",
            labelEn = "Layers Arrow Up",
            tags = listOf("front", "top", "bring to front", "foreground", "bring forward", "layers arrow up", "layers-arrow-up")
        ),
        IconEntry(
            key = "lucide:layers-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layers_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layers Minus",
            labelEn = "Layers Minus",
            tags = listOf("layers-minus", "layers minus", "layers")
        ),
        IconEntry(
            key = "lucide:layers-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layers_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layers Plus",
            labelEn = "Layers Plus",
            tags = listOf("layers", "upgrade", "layers plus", "positive", "layers-plus")
        ),
    )

    private fun getPart10(): List<IconEntry> = listOf(
        IconEntry(
            key = "lucide:layers",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layers,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layers",
            labelEn = "Layers",
            tags = listOf("layers", "pages", "paperwork", "sheets", "pile", "stack")
        ),
        IconEntry(
            key = "lucide:layout-arrow-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_arrow_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout Arrow Down",
            labelEn = "Layout Arrow Down",
            tags = listOf("flexbox", "flex-direction", "flex", "layout", "layout-arrow-down", "column", "layout arrow down")
        ),
        IconEntry(
            key = "lucide:layout-arrow-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_arrow_right,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout Arrow Right",
            labelEn = "Layout Arrow Right",
            tags = listOf("layout-arrow-right", "flexbox", "row", "flex-direction", "layout arrow right", "flex", "layout")
        ),
        IconEntry(
            key = "lucide:layout-dashboard",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_dashboard,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout Dashboard",
            labelEn = "Layout Dashboard",
            tags = listOf("brick", "layout-dashboard", "masonry", "layout dashboard")
        ),
        IconEntry(
            key = "lucide:layout-freeform",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_freeform,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout Freeform",
            labelEn = "Layout Freeform",
            tags = listOf("freeform", "free", "layout-freeform", "absolute", "layout", "position", "layout freeform")
        ),
        IconEntry(
            key = "lucide:layout-grid-circles",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_grid_circles,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layout Grid Circles",
            labelEn = "Layout Grid Circles",
            tags = listOf("dot matrix", "layout-grid-circles", "collection", "dashboard", "menu", "layout grid circles", "dots")
        ),
        IconEntry(
            key = "lucide:layout-grid",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_grid,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout Grid",
            labelEn = "Layout Grid",
            tags = listOf("layout-grid", "start", "app", "layout grid", "home")
        ),
        IconEntry(
            key = "lucide:layout-list",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_list,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout List",
            labelEn = "Layout List",
            tags = listOf("layout list", "layout-list", "tasks", "image", "todo", "pending", "items")
        ),
        IconEntry(
            key = "lucide:layout-panel-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_panel_left,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Layout Panel Left",
            labelEn = "Layout Panel Left",
            tags = listOf("layout panel left", "start", "grid", "app", "layout-panel-left", "home")
        ),
        IconEntry(
            key = "lucide:layout-panel-top",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_panel_top,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layout Panel Top",
            labelEn = "Layout Panel Top",
            tags = listOf("window", "section", "layout panel top", "template", "grid", "layout-panel-top", "webpage")
        ),
        IconEntry(
            key = "lucide:layout-template",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_layout_template,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Layout Template",
            labelEn = "Layout Template",
            tags = listOf("window", "section", "layout template", "block", "layout-template", "webpage")
        ),
        IconEntry(
            key = "lucide:leaf",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_leaf,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.NATURE,
            labelRu = "Leaf",
            labelEn = "Leaf",
            tags = listOf("sustainability", "plant", "leaf", "nature", "energy", "autumn")
        ),
        IconEntry(
            key = "lucide:leafy-green",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_leafy_green,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.FOOD,
            labelRu = "Leafy Green",
            labelEn = "Leafy Green",
            tags = listOf("chard", "cabbage", "vegetable", "leafy-green", "lettuce", "leafy green", "salad")
        ),
        IconEntry(
            key = "lucide:lectern",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lectern,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.PEOPLE,
            labelRu = "Lectern",
            labelEn = "Lectern",
            tags = listOf("lectern", "podium", "stand", "pulpit")
        ),
        IconEntry(
            key = "lucide:lens-concave",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lens_concave,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Lens Concave",
            labelEn = "Lens Concave",
            tags = listOf("magnification", "lens concave", "optics", "lens-concave", "lens", "light", "concave")
        ),
        IconEntry(
            key = "lucide:lens-convex",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lens_convex,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.STUDY,
            labelRu = "Lens Convex",
            labelEn = "Lens Convex",
            tags = listOf("magnification", "lens-convex", "lens convex", "focus", "convex", "optics", "lens")
        ),
        IconEntry(
            key = "lucide:lens",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lens,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Lens",
            labelEn = "Lens",
            tags = listOf("focus", "optics", "camera", "reflection", "lens", "photography")
        ),
        IconEntry(
            key = "lucide:letter-spacing",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_letter_spacing,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Letter Spacing",
            labelEn = "Letter Spacing",
            tags = listOf("lettering", "letters", "tracking", "kerning", "letter spacing", "letter-spacing", "design")
        ),
        IconEntry(
            key = "lucide:library-big",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_library_big,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Library Big",
            labelEn = "Library Big",
            tags = listOf("library big", "library-big")
        ),
        IconEntry(
            key = "lucide:life-buoy",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_life_buoy,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HEALTH,
            labelRu = "Life Buoy",
            labelEn = "Life Buoy",
            tags = listOf("life belt", "life-buoy", "help", "rescue", "life buoy", "lifesaver", "preserver")
        ),
        IconEntry(
            key = "lucide:ligature",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_ligature,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Ligature",
            labelEn = "Ligature",
            tags = listOf("alternates", "ligature", "alternatives")
        ),
        IconEntry(
            key = "lucide:lightbulb-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lightbulb_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Lightbulb Off",
            labelEn = "Lightbulb Off",
            tags = listOf("lightbulb-off", "lightbulb off")
        ),
        IconEntry(
            key = "lucide:lighthouse",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_lighthouse,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.HOME,
            labelRu = "Lighthouse",
            labelEn = "Lighthouse",
            tags = listOf("beacon", "coast", "navigation", "lighthouse", "tower")
        ),
        IconEntry(
            key = "lucide:line-dot-bottom-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_dot_bottom_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Line Dot Bottom Vertical",
            labelEn = "Line Dot Bottom Vertical",
            tags = listOf("station", "waypoint", "line dot bottom vertical", "version", "line-dot-bottom-vertical", "control", "code")
        ),
        IconEntry(
            key = "lucide:line-dot-left-horizontal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_dot_left_horizontal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Line Dot Left Horizontal",
            labelEn = "Line Dot Left Horizontal",
            tags = listOf("shape", "line dot left horizontal", "symbol", "line-dot-left-horizontal", "line", "navigation", "left")
        ),
        IconEntry(
            key = "lucide:line-dot-right-horizontal",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_dot_right_horizontal,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Line Dot Right Horizontal",
            labelEn = "Line Dot Right Horizontal",
            tags = listOf("station", "version control", "waypoint", "line dot right horizontal", "line-dot-right-horizontal", "stop", "code")
        ),
        IconEntry(
            key = "lucide:line-dot-top-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_dot_top_vertical,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "Line Dot Top Vertical",
            labelEn = "Line Dot Top Vertical",
            tags = listOf("indicator", "line dot top vertical", "arrow", "line-dot-top-vertical", "direction", "code", "pointer")
        ),
        IconEntry(
            key = "lucide:line-height",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_height,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Line Height",
            labelEn = "Line Height",
            tags = listOf("line-height", "line height", "spacing", "letters", "characters", "leading", "paragraph")
        ),
        IconEntry(
            key = "lucide:line-squiggle",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_squiggle,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Line Squiggle",
            labelEn = "Line Squiggle",
            tags = listOf("line-squiggle", "annotate", "line", "line squiggle", "curve", "doodle", "snakes")
        ),
        IconEntry(
            key = "lucide:line-style",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_line_style,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "Line Style",
            labelEn = "Line Style",
            tags = listOf("stroke", "line style", "line", "style", "dashed", "line-style", "border")
        ),
        IconEntry(
            key = "lucide:link-2-off",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_link_2_off,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Link 2 Off",
            labelEn = "Link 2 Off",
            tags = listOf("link-2-off", "link 2 off", "unchain")
        ),
        IconEntry(
            key = "lucide:link",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_link,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "Link",
            labelEn = "Link",
            tags = listOf("chain", "link", "url")
        ),
        IconEntry(
            key = "lucide:list-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_check,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Check",
            labelEn = "List Check",
            tags = listOf("bom", "list-check", "list check", "list")
        ),
        IconEntry(
            key = "lucide:list-checks",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_checks,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Checks",
            labelEn = "List Checks",
            tags = listOf("list checks", "pending", "list-checks", "items")
        ),
        IconEntry(
            key = "lucide:list-chevrons-down-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_chevrons_down_up,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Chevrons Down Up",
            labelEn = "List Chevrons Down Up",
            tags = listOf("disclosure", "list chevrons down up", "collapse", "expand", "details", "items", "list-chevrons-down-up")
        ),
        IconEntry(
            key = "lucide:list-chevrons-up-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_chevrons_up_down,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Chevrons Up Down",
            labelEn = "List Chevrons Up Down",
            tags = listOf("disclosure", "list-chevrons-up-down", "collapse", "list chevrons up down", "expand", "details", "items")
        ),
        IconEntry(
            key = "lucide:list-clock",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_clock,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Clock",
            labelEn = "List Clock",
            tags = listOf("activity", "updated", "list clock", "log", "list-clock", "revision", "recent")
        ),
        IconEntry(
            key = "lucide:list-collapse",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_collapse,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Collapse",
            labelEn = "List Collapse",
            tags = listOf("disclosure", "list-collapse", "collapse", "list collapse", "expand", "details", "items")
        ),
        IconEntry(
            key = "lucide:list-end",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_end,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "List End",
            labelEn = "List End",
            tags = listOf("playlist", "bottom", "list end", "queue", "list-end", "end")
        ),
        IconEntry(
            key = "lucide:list-filter-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_filter_plus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Filter Plus",
            labelEn = "List Filter Plus",
            tags = listOf("list-filter-plus", "filter", "list filter plus")
        ),
        IconEntry(
            key = "lucide:list-filter",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_filter,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Filter",
            labelEn = "List Filter",
            tags = listOf("list filter", "list-filter")
        ),
        IconEntry(
            key = "lucide:list-indent-decrease",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_indent_decrease,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "List Indent Decrease",
            labelEn = "List Indent Decrease",
            tags = listOf("list indent decrease", "tab", "list-indent-decrease", "text")
        ),
        IconEntry(
            key = "lucide:list-indent-increase",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_indent_increase,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.CODE,
            labelRu = "List Indent Increase",
            labelEn = "List Indent Increase",
            tags = listOf("list indent increase", "list-indent-increase", "tab", "text")
        ),
        IconEntry(
            key = "lucide:list-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_minus,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "List Minus",
            labelEn = "List Minus",
            tags = listOf("playlist", "unqueue", "song", "list minus", "list-minus")
        ),
        IconEntry(
            key = "lucide:list-music",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_music,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ART,
            labelRu = "List Music",
            labelEn = "List Music",
            tags = listOf("music", "playlist", "list music", "queue", "playback", "list-music", "audio")
        ),
        IconEntry(
            key = "lucide:list-ordered",
            legacyKey = null,
            drawableRes = R.drawable.ic_l_list_ordered,
            namespace = IconNamespace.LUCIDE,
            category = IconCategory.ROUTINE,
            labelRu = "List Ordered",
            labelEn = "List Ordered",
            tags = listOf("list ordered", "order", "queue", "list-ordered")
        ),
        IconEntry(
            key = "tabler:address-book",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_address_book,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Address Book",
            labelEn = "Address Book",
            tags = listOf("знания", "learn", "учеба", "address book", "наука", "study", "address-book", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:alphabet-runes",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_alphabet_runes,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Alphabet Runes",
            labelEn = "Alphabet Runes",
            tags = listOf("alphabet runes", "alphabet-runes", "тренировка", "упражнения", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:api-book",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_api_book,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Api Book",
            labelEn = "Api Book",
            tags = listOf("знания", "learn", "учеба", "api-book", "api book", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:app-window",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_app_window,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "App Window",
            labelEn = "App Window",
            tags = listOf("clean", "app window", "дом", "чистота", "порядок", "быт", "app-window", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:app-window-bottom",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_app_window_bottom,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "App Window Bottom",
            labelEn = "App Window Bottom",
            tags = listOf("clean", "дом", "app-window-bottom", "чистота", "порядок", "быт", "уборка", "app window bottom", "home")
        ),
        IconEntry(
            key = "tabler:app-window-bottom-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_app_window_bottom_left,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "App Window Bottom Left",
            labelEn = "App Window Bottom Left",
            tags = listOf("clean", "app-window-bottom-left", "дом", "чистота", "порядок", "app window bottom left", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:app-window-bottom-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_app_window_bottom_right,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "App Window Bottom Right",
            labelEn = "App Window Bottom Right",
            tags = listOf("clean", "дом", "чистота", "порядок", "быт", "уборка", "app-window-bottom-right", "app window bottom right", "home")
        ),
        IconEntry(
            key = "tabler:app-window-center",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_app_window_center,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "App Window Center",
            labelEn = "App Window Center",
            tags = listOf("clean", "дом", "app-window-center", "чистота", "быт", "порядок", "уборка", "app window center", "home")
        ),
        IconEntry(
            key = "tabler:arrow-down-rhombus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_arrow_down_rhombus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Arrow Down Rhombus",
            labelEn = "Arrow Down Rhombus",
            tags = listOf("arrow-down-rhombus", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт", "arrow down rhombus")
        ),
        IconEntry(
            key = "tabler:arrow-left-rhombus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_arrow_left_rhombus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Arrow Left Rhombus",
            labelEn = "Arrow Left Rhombus",
            tags = listOf("arrow left rhombus", "дорога", "travel", "arrow-left-rhombus", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:arrow-right-rhombus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_arrow_right_rhombus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Arrow Right Rhombus",
            labelEn = "Arrow Right Rhombus",
            tags = listOf("дорога", "arrow right rhombus", "travel", "путешествия", "отпуск", "поездка", "транспорт", "arrow-right-rhombus")
        ),
        IconEntry(
            key = "tabler:arrow-up-rhombus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_arrow_up_rhombus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Arrow Up Rhombus",
            labelEn = "Arrow Up Rhombus",
            tags = listOf("arrow up rhombus", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт", "arrow-up-rhombus")
        ),
        IconEntry(
            key = "tabler:atom",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_atom,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Atom",
            labelEn = "Atom",
            tags = listOf("знания", "learn", "учеба", "atom", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:atom-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_atom_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Atom 2",
            labelEn = "Atom 2",
            tags = listOf("atom 2", "знания", "learn", "учеба", "наука", "study", "atom-2", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:baby-bottle",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_baby_bottle,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Baby Bottle",
            labelEn = "Baby Bottle",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "baby-bottle", "еда", "baby bottle", "nutrition")
        ),
        IconEntry(
            key = "tabler:baby-carriage",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_baby_carriage,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Baby Carriage",
            labelEn = "Baby Carriage",
            tags = listOf("дорога", "baby carriage", "travel", "baby-carriage", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:ball-american-football",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_american_football,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball American Football",
            labelEn = "Ball American Football",
            tags = listOf("ball-american-football", "ball american football", "тренировка", "упражнения", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:ball-baseball",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_baseball,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball Baseball",
            labelEn = "Ball Baseball",
            tags = listOf("ball-baseball", "тренировка", "упражнения", "ball baseball", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:ball-basketball",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_basketball,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball Basketball",
            labelEn = "Ball Basketball",
            tags = listOf("ball basketball", "тренировка", "ball-basketball", "упражнения", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:ball-bowling",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_bowling,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball Bowling",
            labelEn = "Ball Bowling",
            tags = listOf("тренировка", "ball-bowling", "упражнения", "ball bowling", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:ball-football",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_football,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball Football",
            labelEn = "Ball Football",
            tags = listOf("ball football", "ball-football", "тренировка", "упражнения", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:ball-tennis",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_tennis,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball Tennis",
            labelEn = "Ball Tennis",
            tags = listOf("активность", "ball-tennis", "тренировка", "упражнения", "fitness", "спорт", "ball tennis", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:ball-volleyball",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ball_volleyball,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ball Volleyball",
            labelEn = "Ball Volleyball",
            tags = listOf("ball volleyball", "тренировка", "упражнения", "fitness", "спорт", "ball-volleyball", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:bath",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bath,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Bath",
            labelEn = "Bath",
            tags = listOf("clean", "дом", "чистота", "порядок", "быт", "уборка", "bath", "home")
        ),
        IconEntry(
            key = "tabler:beach",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_beach,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Beach",
            labelEn = "Beach",
            tags = listOf("beach", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:beach-bag",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_beach_bag,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Beach Bag",
            labelEn = "Beach Bag",
            tags = listOf("beach-bag", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт", "beach bag")
        ),
        IconEntry(
            key = "tabler:bed",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bed,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SLEEP,
            labelRu = "Bed",
            labelEn = "Bed",
            tags = listOf("rest", "спать", "sleep", "отдых", "сон", "bed", "релакс", "ночь")
        ),
        IconEntry(
            key = "tabler:bed-flat",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bed_flat,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SLEEP,
            labelRu = "Bed Flat",
            labelEn = "Bed Flat",
            tags = listOf("rest", "спать", "bed flat", "sleep", "bed-flat", "отдых", "сон", "релакс", "ночь")
        ),
        IconEntry(
            key = "tabler:bell-school",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bell_school,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bell School",
            labelEn = "Bell School",
            tags = listOf("bell school", "знания", "learn", "учеба", "bell-school", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bike",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bike,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Bike",
            labelEn = "Bike",
            tags = listOf("тренировка", "упражнения", "bike", "fitness", "спорт", "активность", "workout", "зал")
        ),
        IconEntry(
            key = "tabler:binary-tree",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_binary_tree,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Binary Tree",
            labelEn = "Binary Tree",
            tags = listOf("binary-tree", "питомец", "животные", "природа", "binary tree", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:binary-tree-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_binary_tree_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Binary Tree 2",
            labelEn = "Binary Tree 2",
            tags = listOf("питомец", "binary-tree-2", "животные", "binary tree 2", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:book",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_book,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Book",
            labelEn = "Book",
            tags = listOf("знания", "learn", "учеба", "наука", "study", "book", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:book-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_book_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Book 2",
            labelEn = "Book 2",
            tags = listOf("знания", "learn", "учеба", "book 2", "book-2", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:book-clone",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_book_clone,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Book Clone",
            labelEn = "Book Clone",
            tags = listOf("book clone", "book-clone", "знания", "learn", "учеба", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:book-download",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_book_download,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Book Download",
            labelEn = "Book Download",
            tags = listOf("знания", "book-download", "book download", "learn", "учеба", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:book-upload",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_book_upload,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Book Upload",
            labelEn = "Book Upload",
            tags = listOf("знания", "learn", "учеба", "наука", "study", "book-upload", "book upload", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmark",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmark,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmark",
            labelEn = "Bookmark",
            tags = listOf("знания", "learn", "учеба", "bookmark", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmark-ai",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmark_ai,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmark Ai",
            labelEn = "Bookmark Ai",
            tags = listOf("знания", "learn", "учеба", "bookmark-ai", "bookmark ai", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmark-edit",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmark_edit,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmark Edit",
            labelEn = "Bookmark Edit",
            tags = listOf("знания", "learn", "учеба", "bookmark edit", "наука", "bookmark-edit", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmark-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmark_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmark Minus",
            labelEn = "Bookmark Minus",
            tags = listOf("знания", "learn", "учеба", "bookmark minus", "наука", "bookmark-minus", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmark-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmark_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmark Plus",
            labelEn = "Bookmark Plus",
            tags = listOf("знания", "learn", "учеба", "bookmark plus", "наука", "study", "bookmark-plus", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmark-question",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmark_question,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmark Question",
            labelEn = "Bookmark Question",
            tags = listOf("знания", "learn", "учеба", "bookmark-question", "bookmark question", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bookmarks",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bookmarks,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Bookmarks",
            labelEn = "Bookmarks",
            tags = listOf("знания", "learn", "учеба", "bookmarks", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:books",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_books,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Books",
            labelEn = "Books",
            tags = listOf("знания", "learn", "books", "учеба", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:bottle",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bottle,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Bottle",
            labelEn = "Bottle",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "bottle", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:bread",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bread,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Bread",
            labelEn = "Bread",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "еда", "nutrition", "bread")
        ),
        IconEntry(
            key = "tabler:bubble-tea",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bubble_tea,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Bubble Tea",
            labelEn = "Bubble Tea",
            tags = listOf("питание", "кухня", "диета", "кушать", "bubble-tea", "food", "еда", "bubble tea", "nutrition")
        ),
        IconEntry(
            key = "tabler:bubble-tea-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bubble_tea_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Bubble Tea 2",
            labelEn = "Bubble Tea 2",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "bubble-tea-2", "еда", "bubble tea 2", "nutrition")
        ),
        IconEntry(
            key = "tabler:building-carousel",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_building_carousel,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Building Carousel",
            labelEn = "Building Carousel",
            tags = listOf("дорога", "travel", "building-carousel", "путешествия", "отпуск", "транспорт", "поездка", "building carousel")
        ),
        IconEntry(
            key = "tabler:bus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Bus",
            labelEn = "Bus",
            tags = listOf("дорога", "travel", "bus", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:bus-stop",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_bus_stop,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Bus Stop",
            labelEn = "Bus Stop",
            tags = listOf("дорога", "bus-stop", "travel", "bus stop", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:businessplan",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_businessplan,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Businessplan",
            labelEn = "Businessplan",
            tags = listOf("дорога", "travel", "businessplan", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:cactus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cactus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Cactus",
            labelEn = "Cactus",
            tags = listOf("питомец", "животные", "cactus", "природа", "nature", "растения", "зелень")
        ),
    )

    private fun getPart11(): List<IconEntry> = listOf(
        IconEntry(
            key = "tabler:calculator",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_calculator,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Calculator",
            labelEn = "Calculator",
            tags = listOf("знания", "learn", "учеба", "calculator", "наука", "study", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:camera",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera",
            labelEn = "Camera",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera")
        ),
        IconEntry(
            key = "tabler:camera-ai",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_ai,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Ai",
            labelEn = "Camera Ai",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera ai", "рисовать", "camera-ai")
        ),
        IconEntry(
            key = "tabler:camera-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Bitcoin",
            labelEn = "Camera Bitcoin",
            tags = listOf("музыка", "music", "camera-bitcoin", "арт", "хобби", "camera bitcoin", "art", "творчество", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-bolt",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_bolt,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Bolt",
            labelEn = "Camera Bolt",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera bolt", "camera-bolt")
        ),
        IconEntry(
            key = "tabler:camera-cancel",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_cancel,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Cancel",
            labelEn = "Camera Cancel",
            tags = listOf("музыка", "music", "арт", "хобби", "camera cancel", "art", "творчество", "рисовать", "camera-cancel")
        ),
        IconEntry(
            key = "tabler:camera-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_check,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Check",
            labelEn = "Camera Check",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera check", "camera-check", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-code",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_code,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Code",
            labelEn = "Camera Code",
            tags = listOf("музыка", "camera code", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera-code")
        ),
        IconEntry(
            key = "tabler:camera-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_cog,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Cog",
            labelEn = "Camera Cog",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera cog", "рисовать", "camera-cog")
        ),
        IconEntry(
            key = "tabler:camera-dollar",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_dollar,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Dollar",
            labelEn = "Camera Dollar",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera dollar", "рисовать", "camera-dollar")
        ),
        IconEntry(
            key = "tabler:camera-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_down,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Down",
            labelEn = "Camera Down",
            tags = listOf("музыка", "music", "арт", "хобби", "camera-down", "art", "творчество", "camera down", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-exclamation",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_exclamation,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Exclamation",
            labelEn = "Camera Exclamation",
            tags = listOf("музыка", "camera exclamation", "music", "арт", "хобби", "art", "творчество", "camera-exclamation", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_heart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Heart",
            labelEn = "Camera Heart",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera-heart", "рисовать", "camera heart")
        ),
        IconEntry(
            key = "tabler:camera-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Minus",
            labelEn = "Camera Minus",
            tags = listOf("музыка", "camera-minus", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera minus")
        ),
        IconEntry(
            key = "tabler:camera-moon",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_moon,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Moon",
            labelEn = "Camera Moon",
            tags = listOf("музыка", "music", "арт", "хобби", "camera moon", "art", "творчество", "рисовать", "camera-moon")
        ),
        IconEntry(
            key = "tabler:camera-pause",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_pause,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Pause",
            labelEn = "Camera Pause",
            tags = listOf("музыка", "music", "арт", "хобби", "camera-pause", "творчество", "art", "camera pause", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-pin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_pin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Pin",
            labelEn = "Camera Pin",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera-pin", "camera pin", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Plus",
            labelEn = "Camera Plus",
            tags = listOf("музыка", "music", "арт", "camera-plus", "хобби", "art", "творчество", "рисовать", "camera plus")
        ),
        IconEntry(
            key = "tabler:camera-question",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_question,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Question",
            labelEn = "Camera Question",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera question", "camera-question")
        ),
        IconEntry(
            key = "tabler:camera-rotate",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_rotate,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Rotate",
            labelEn = "Camera Rotate",
            tags = listOf("музыка", "camera-rotate", "music", "арт", "хобби", "art", "творчество", "camera rotate", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_search,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Search",
            labelEn = "Camera Search",
            tags = listOf("музыка", "music", "арт", "хобби", "camera search", "art", "творчество", "рисовать", "camera-search")
        ),
        IconEntry(
            key = "tabler:camera-selfie",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_selfie,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Selfie",
            labelEn = "Camera Selfie",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera-selfie", "camera selfie")
        ),
        IconEntry(
            key = "tabler:camera-share",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_share,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Share",
            labelEn = "Camera Share",
            tags = listOf("музыка", "camera share", "music", "арт", "хобби", "art", "творчество", "рисовать", "camera-share")
        ),
        IconEntry(
            key = "tabler:camera-spark",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_spark,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Spark",
            labelEn = "Camera Spark",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera-spark", "camera spark", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-star",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_star,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Star",
            labelEn = "Camera Star",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "camera-star", "рисовать", "camera star")
        ),
        IconEntry(
            key = "tabler:camera-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_up,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera Up",
            labelEn = "Camera Up",
            tags = listOf("музыка", "music", "арт", "хобби", "camera up", "творчество", "art", "camera-up", "рисовать")
        ),
        IconEntry(
            key = "tabler:camera-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camera_x,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Camera X",
            labelEn = "Camera X",
            tags = listOf("музыка", "music", "camera-x", "арт", "хобби", "camera x", "art", "творчество", "рисовать")
        ),
        IconEntry(
            key = "tabler:camper",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_camper,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Camper",
            labelEn = "Camper",
            tags = listOf("дорога", "camper", "travel", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:campfire",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_campfire,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Campfire",
            labelEn = "Campfire",
            tags = listOf("campfire", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car",
            labelEn = "Car",
            tags = listOf("дорога", "travel", "car", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-4wd",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_4wd,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car 4Wd",
            labelEn = "Car 4Wd",
            tags = listOf("дорога", "travel", "car-4wd", "путешествия", "отпуск", "car 4wd", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-crane",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_crane,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Crane",
            labelEn = "Car Crane",
            tags = listOf("car crane", "дорога", "travel", "car-crane", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-crash",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_crash,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Crash",
            labelEn = "Car Crash",
            tags = listOf("дорога", "travel", "car crash", "путешествия", "отпуск", "car-crash", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-door",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_door,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Car Door",
            labelEn = "Car Door",
            tags = listOf("car door", "clean", "car-door", "дом", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:car-fan",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_fan,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Fan",
            labelEn = "Car Fan",
            tags = listOf("car-fan", "дорога", "travel", "путешествия", "отпуск", "транспорт", "поездка", "car fan")
        ),
        IconEntry(
            key = "tabler:car-fan-1",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_fan_1,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Fan 1",
            labelEn = "Car Fan 1",
            tags = listOf("дорога", "travel", "путешествия", "отпуск", "car fan 1", "поездка", "транспорт", "car-fan-1")
        ),
        IconEntry(
            key = "tabler:car-fan-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_fan_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Fan 2",
            labelEn = "Car Fan 2",
            tags = listOf("дорога", "travel", "car fan 2", "путешествия", "отпуск", "поездка", "транспорт", "car-fan-2")
        ),
        IconEntry(
            key = "tabler:car-fan-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_fan_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Fan 3",
            labelEn = "Car Fan 3",
            tags = listOf("дорога", "travel", "car-fan-3", "путешествия", "отпуск", "поездка", "транспорт", "car fan 3")
        ),
        IconEntry(
            key = "tabler:car-fan-auto",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_fan_auto,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Fan Auto",
            labelEn = "Car Fan Auto",
            tags = listOf("дорога", "car fan auto", "travel", "путешествия", "отпуск", "транспорт", "поездка", "car-fan-auto")
        ),
        IconEntry(
            key = "tabler:car-garage",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_garage,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Garage",
            labelEn = "Car Garage",
            tags = listOf("car garage", "дорога", "travel", "путешествия", "отпуск", "car-garage", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-lifter",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_lifter,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Lifter",
            labelEn = "Car Lifter",
            tags = listOf("дорога", "car lifter", "travel", "путешествия", "отпуск", "поездка", "транспорт", "car-lifter")
        ),
        IconEntry(
            key = "tabler:car-off-road",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_off_road,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Off Road",
            labelEn = "Car Off Road",
            tags = listOf("дорога", "car off road", "travel", "путешествия", "отпуск", "car-off-road", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-suspension",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_suspension,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Suspension",
            labelEn = "Car Suspension",
            tags = listOf("дорога", "travel", "car suspension", "путешествия", "отпуск", "car-suspension", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:car-suv",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_suv,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Suv",
            labelEn = "Car Suv",
            tags = listOf("дорога", "travel", "путешествия", "отпуск", "car-suv", "поездка", "транспорт", "car suv")
        ),
        IconEntry(
            key = "tabler:car-turbine",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_car_turbine,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Car Turbine",
            labelEn = "Car Turbine",
            tags = listOf("дорога", "car-turbine", "travel", "car turbine", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:carambola",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_carambola,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Carambola",
            labelEn = "Carambola",
            tags = listOf("carambola", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:caravan",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caravan,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caravan",
            labelEn = "Caravan",
            tags = listOf("дорога", "travel", "caravan", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:cardboards",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cardboards,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cardboards",
            labelEn = "Cardboards",
            tags = listOf("бюджет", "finance", "доход", "деньги", "финансы", "cardboards", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cards",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cards,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cards",
            labelEn = "Cards",
            tags = listOf("бюджет", "cards", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:caret-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caret_down,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caret Down",
            labelEn = "Caret Down",
            tags = listOf("дорога", "caret down", "travel", "путешествия", "отпуск", "поездка", "транспорт", "caret-down")
        ),
        IconEntry(
            key = "tabler:caret-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caret_left,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caret Left",
            labelEn = "Caret Left",
            tags = listOf("дорога", "travel", "caret left", "путешествия", "отпуск", "поездка", "caret-left", "транспорт")
        ),
        IconEntry(
            key = "tabler:caret-left-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caret_left_right,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caret Left Right",
            labelEn = "Caret Left Right",
            tags = listOf("дорога", "caret left right", "travel", "caret-left-right", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:caret-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caret_right,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caret Right",
            labelEn = "Caret Right",
            tags = listOf("дорога", "travel", "caret-right", "caret right", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:caret-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caret_up,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caret Up",
            labelEn = "Caret Up",
            tags = listOf("дорога", "caret-up", "travel", "путешествия", "отпуск", "caret up", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:caret-up-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_caret_up_down,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Caret Up Down",
            labelEn = "Caret Up Down",
            tags = listOf("дорога", "travel", "путешествия", "отпуск", "caret up down", "поездка", "транспорт", "caret-up-down")
        ),
        IconEntry(
            key = "tabler:carousel-horizontal",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_carousel_horizontal,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Carousel Horizontal",
            labelEn = "Carousel Horizontal",
            tags = listOf("дорога", "carousel-horizontal", "travel", "путешествия", "отпуск", "поездка", "транспорт", "carousel horizontal")
        ),
        IconEntry(
            key = "tabler:carousel-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_carousel_vertical,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Carousel Vertical",
            labelEn = "Carousel Vertical",
            tags = listOf("дорога", "carousel vertical", "travel", "carousel-vertical", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:carrot",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_carrot,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Carrot",
            labelEn = "Carrot",
            tags = listOf("дорога", "travel", "carrot", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:cash",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash",
            labelEn = "Cash",
            tags = listOf("бюджет", "cash", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-banknote",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote",
            labelEn = "Cash Banknote",
            tags = listOf("бюджет", "cash-banknote", "доход", "деньги", "финансы", "finance", "cash banknote", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-banknote-edit",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote_edit,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote Edit",
            labelEn = "Cash Banknote Edit",
            tags = listOf("бюджет", "доход", "деньги", "cash banknote edit", "финансы", "finance", "cash-banknote-edit", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-banknote-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote_heart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote Heart",
            labelEn = "Cash Banknote Heart",
            tags = listOf("бюджет", "cash banknote heart", "cash-banknote-heart", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-banknote-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote Minus",
            labelEn = "Cash Banknote Minus",
            tags = listOf("бюджет", "cash banknote minus", "доход", "деньги", "финансы", "finance", "cash-banknote-minus", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-banknote-move",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote_move,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote Move",
            labelEn = "Cash Banknote Move",
            tags = listOf("бюджет", "доход", "деньги", "cash-banknote-move", "финансы", "finance", "money", "копить", "cash banknote move")
        ),
        IconEntry(
            key = "tabler:cash-banknote-move-back",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote_move_back,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote Move Back",
            labelEn = "Cash Banknote Move Back",
            tags = listOf("бюджет", "доход", "деньги", "cash banknote move back", "финансы", "cash-banknote-move-back", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-banknote-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_banknote_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Banknote Plus",
            labelEn = "Cash Banknote Plus",
            tags = listOf("бюджет", "finance", "доход", "деньги", "финансы", "cash banknote plus", "cash-banknote-plus", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-coin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_coin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Coin",
            labelEn = "Cash Coin",
            tags = listOf("бюджет", "cash coin", "доход", "деньги", "финансы", "finance", "cash-coin", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-edit",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_edit,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Edit",
            labelEn = "Cash Edit",
            tags = listOf("бюджет", "cash edit", "доход", "деньги", "финансы", "finance", "cash-edit", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_heart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Heart",
            labelEn = "Cash Heart",
            tags = listOf("бюджет", "cash-heart", "доход", "cash heart", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Minus",
            labelEn = "Cash Minus",
            tags = listOf("бюджет", "finance", "доход", "деньги", "cash minus", "финансы", "cash-minus", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-move",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_move,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Move",
            labelEn = "Cash Move",
            tags = listOf("бюджет", "cash move", "доход", "деньги", "финансы", "cash-move", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-move-back",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_move_back,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Move Back",
            labelEn = "Cash Move Back",
            tags = listOf("бюджет", "cash-move-back", "доход", "деньги", "cash move back", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Plus",
            labelEn = "Cash Plus",
            tags = listOf("бюджет", "cash-plus", "finance", "доход", "деньги", "финансы", "cash plus", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cash-register",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cash_register,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cash Register",
            labelEn = "Cash Register",
            tags = listOf("бюджет", "доход", "cash-register", "деньги", "cash register", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cat",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cat,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Cat",
            labelEn = "Cat",
            tags = listOf("питомец", "животные", "cat", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:category",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_category,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Category",
            labelEn = "Category",
            tags = listOf("питомец", "животные", "природа", "nature", "растения", "зелень", "category")
        ),
        IconEntry(
            key = "tabler:category-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_category_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Category 2",
            labelEn = "Category 2",
            tags = listOf("питомец", "животные", "природа", "category 2", "nature", "category-2", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:category-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_category_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Category Minus",
            labelEn = "Category Minus",
            tags = listOf("питомец", "животные", "природа", "category-minus", "category minus", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:category-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_category_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Category Plus",
            labelEn = "Category Plus",
            tags = listOf("питомец", "животные", "природа", "category plus", "category-plus", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:certificate",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_certificate,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Certificate",
            labelEn = "Certificate",
            tags = listOf("питомец", "животные", "природа", "certificate", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:certificate-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_certificate_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Certificate 2",
            labelEn = "Certificate 2",
            tags = listOf("питомец", "животные", "certificate-2", "природа", "nature", "certificate 2", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:chalkboard-teacher",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chalkboard_teacher,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Chalkboard Teacher",
            labelEn = "Chalkboard Teacher",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "еда", "chalkboard-teacher", "chalkboard teacher", "nutrition")
        ),
        IconEntry(
            key = "tabler:chart-arcs",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_arcs,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Arcs",
            labelEn = "Chart Arcs",
            tags = listOf("бюджет", "chart-arcs", "chart arcs", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-arcs-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_arcs_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Arcs 3",
            labelEn = "Chart Arcs 3",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "finance", "chart-arcs-3", "money", "копить", "chart arcs 3")
        ),
        IconEntry(
            key = "tabler:chart-area",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_area,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Area",
            labelEn = "Chart Area",
            tags = listOf("бюджет", "chart-area", "chart area", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-area-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_area_line,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Area Line",
            labelEn = "Chart Area Line",
            tags = listOf("бюджет", "chart area line", "доход", "деньги", "финансы", "chart-area-line", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-arrows",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_arrows,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Arrows",
            labelEn = "Chart Arrows",
            tags = listOf("chart-arrows", "бюджет", "доход", "деньги", "chart arrows", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-arrows-vertical",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_arrows_vertical,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Arrows Vertical",
            labelEn = "Chart Arrows Vertical",
            tags = listOf("chart-arrows-vertical", "бюджет", "доход", "деньги", "chart arrows vertical", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-bar",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_bar,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Bar",
            labelEn = "Chart Bar",
            tags = listOf("бюджет", "chart bar", "доход", "деньги", "финансы", "finance", "chart-bar", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-bar-popular",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_bar_popular,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Bar Popular",
            labelEn = "Chart Bar Popular",
            tags = listOf("бюджет", "доход", "деньги", "chart bar popular", "финансы", "finance", "chart-bar-popular", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-bubble",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_bubble,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Bubble",
            labelEn = "Chart Bubble",
            tags = listOf("бюджет", "chart bubble", "доход", "деньги", "финансы", "chart-bubble", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-candle",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_candle,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Candle",
            labelEn = "Chart Candle",
            tags = listOf("chart-candle", "бюджет", "доход", "деньги", "финансы", "finance", "money", "копить", "chart candle")
        ),
        IconEntry(
            key = "tabler:chart-circles",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_circles,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Circles",
            labelEn = "Chart Circles",
            tags = listOf("бюджет", "chart circles", "chart-circles", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-cohort",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_cohort,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Cohort",
            labelEn = "Chart Cohort",
            tags = listOf("бюджет", "chart-cohort", "доход", "деньги", "финансы", "finance", "money", "копить", "chart cohort")
        ),
        IconEntry(
            key = "tabler:chart-column",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_column,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Column",
            labelEn = "Chart Column",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "finance", "money", "копить", "chart-column", "chart column")
        ),
        IconEntry(
            key = "tabler:chart-covariate",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_covariate,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Covariate",
            labelEn = "Chart Covariate",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "finance", "chart-covariate", "money", "копить", "chart covariate")
        ),
        IconEntry(
            key = "tabler:chart-donut",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_donut,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Donut",
            labelEn = "Chart Donut",
            tags = listOf("бюджет", "chart donut", "chart-donut", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-donut-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_donut_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Donut 2",
            labelEn = "Chart Donut 2",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "money", "finance", "chart-donut-2", "chart donut 2", "копить")
        ),
        IconEntry(
            key = "tabler:chart-donut-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_donut_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Donut 3",
            labelEn = "Chart Donut 3",
            tags = listOf("chart-donut-3", "бюджет", "chart donut 3", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-donut-4",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_donut_4,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Donut 4",
            labelEn = "Chart Donut 4",
            tags = listOf("бюджет", "chart donut 4", "доход", "деньги", "финансы", "chart-donut-4", "finance", "money", "копить")
        ),
    )

    private fun getPart12(): List<IconEntry> = listOf(
        IconEntry(
            key = "tabler:chart-dots",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_dots,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Dots",
            labelEn = "Chart Dots",
            tags = listOf("бюджет", "chart-dots", "доход", "деньги", "chart dots", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-dots-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_dots_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Dots 2",
            labelEn = "Chart Dots 2",
            tags = listOf("бюджет", "доход", "деньги", "chart dots 2", "финансы", "finance", "chart-dots-2", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-dots-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_dots_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Dots 3",
            labelEn = "Chart Dots 3",
            tags = listOf("бюджет", "chart-dots-3", "chart dots 3", "деньги", "доход", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-funnel",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_funnel,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Funnel",
            labelEn = "Chart Funnel",
            tags = listOf("бюджет", "chart-funnel", "доход", "деньги", "финансы", "chart funnel", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-grid-dots",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_grid_dots,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Grid Dots",
            labelEn = "Chart Grid Dots",
            tags = listOf("chart grid dots", "бюджет", "chart-grid-dots", "деньги", "доход", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-histogram",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_histogram,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Histogram",
            labelEn = "Chart Histogram",
            tags = listOf("бюджет", "chart histogram", "chart-histogram", "деньги", "доход", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-infographic",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_infographic,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Infographic",
            labelEn = "Chart Infographic",
            tags = listOf("бюджет", "доход", "деньги", "chart infographic", "финансы", "chart-infographic", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-line",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_line,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Line",
            labelEn = "Chart Line",
            tags = listOf("бюджет", "chart line", "chart-line", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-pie",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_pie,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Pie",
            labelEn = "Chart Pie",
            tags = listOf("бюджет", "chart-pie", "деньги", "доход", "финансы", "money", "finance", "chart pie", "копить")
        ),
        IconEntry(
            key = "tabler:chart-pie-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_pie_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Pie 2",
            labelEn = "Chart Pie 2",
            tags = listOf("бюджет", "chart-pie-2", "доход", "деньги", "финансы", "finance", "chart pie 2", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-pie-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_pie_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Pie 3",
            labelEn = "Chart Pie 3",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "money", "finance", "chart-pie-3", "копить", "chart pie 3")
        ),
        IconEntry(
            key = "tabler:chart-pie-4",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_pie_4,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Pie 4",
            labelEn = "Chart Pie 4",
            tags = listOf("бюджет", "доход", "деньги", "chart pie 4", "финансы", "finance", "chart-pie-4", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-ppf",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_ppf,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Ppf",
            labelEn = "Chart Ppf",
            tags = listOf("бюджет", "chart-ppf", "доход", "деньги", "chart ppf", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-radar",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_radar,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Radar",
            labelEn = "Chart Radar",
            tags = listOf("бюджет", "chart radar", "доход", "деньги", "финансы", "finance", "money", "копить", "chart-radar")
        ),
        IconEntry(
            key = "tabler:chart-sankey",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_sankey,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Sankey",
            labelEn = "Chart Sankey",
            tags = listOf("chart-sankey", "бюджет", "доход", "деньги", "финансы", "finance", "money", "копить", "chart sankey")
        ),
        IconEntry(
            key = "tabler:chart-scatter",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_scatter,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Scatter",
            labelEn = "Chart Scatter",
            tags = listOf("бюджет", "chart-scatter", "доход", "деньги", "финансы", "money", "finance", "chart scatter", "копить")
        ),
        IconEntry(
            key = "tabler:chart-scatter-3d",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_scatter_3d,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Scatter 3D",
            labelEn = "Chart Scatter 3D",
            tags = listOf("chart scatter 3d", "бюджет", "chart-scatter-3d", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:chart-treemap",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chart_treemap,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Chart Treemap",
            labelEn = "Chart Treemap",
            tags = listOf("бюджет", "chart-treemap", "chart treemap", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cheese",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cheese,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Cheese",
            labelEn = "Cheese",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "еда", "cheese", "nutrition")
        ),
        IconEntry(
            key = "tabler:chess",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chess,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Chess",
            labelEn = "Chess",
            tags = listOf("chess")
        ),
        IconEntry(
            key = "tabler:chess-bishop",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chess_bishop,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Bishop",
            labelEn = "Chess Bishop",
            tags = listOf("chess bishop", "chess-bishop")
        ),
        IconEntry(
            key = "tabler:chess-king",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chess_king,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Chess King",
            labelEn = "Chess King",
            tags = listOf("chess king", "chess-king")
        ),
        IconEntry(
            key = "tabler:chess-knight",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chess_knight,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Knight",
            labelEn = "Chess Knight",
            tags = listOf("chess knight", "chess-knight")
        ),
        IconEntry(
            key = "tabler:chess-queen",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chess_queen,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Queen",
            labelEn = "Chess Queen",
            tags = listOf("chess queen", "chess-queen")
        ),
        IconEntry(
            key = "tabler:chess-rook",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_chess_rook,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Chess Rook",
            labelEn = "Chess Rook",
            tags = listOf("chess-rook", "chess rook")
        ),
        IconEntry(
            key = "tabler:christmas-tree",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_christmas_tree,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Christmas Tree",
            labelEn = "Christmas Tree",
            tags = listOf("питомец", "животные", "природа", "nature", "christmas tree", "растения", "зелень", "christmas-tree")
        ),
        IconEntry(
            key = "tabler:circle-caret-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_circle_caret_down,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Circle Caret Down",
            labelEn = "Circle Caret Down",
            tags = listOf("дорога", "circle caret down", "travel", "путешествия", "отпуск", "circle-caret-down", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:circle-caret-left",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_circle_caret_left,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Circle Caret Left",
            labelEn = "Circle Caret Left",
            tags = listOf("дорога", "circle caret left", "travel", "circle-caret-left", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:circle-caret-right",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_circle_caret_right,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Circle Caret Right",
            labelEn = "Circle Caret Right",
            tags = listOf("дорога", "travel", "circle-caret-right", "путешествия", "отпуск", "circle caret right", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:circle-caret-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_circle_caret_up,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Circle Caret Up",
            labelEn = "Circle Caret Up",
            tags = listOf("circle-caret-up", "дорога", "travel", "путешествия", "отпуск", "circle caret up", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:clock-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_clock_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Clock Bitcoin",
            labelEn = "Clock Bitcoin",
            tags = listOf("clock-bitcoin", "бюджет", "доход", "деньги", "финансы", "finance", "money", "копить", "clock bitcoin")
        ),
        IconEntry(
            key = "tabler:cloud-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cloud_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Cloud Bitcoin",
            labelEn = "Cloud Bitcoin",
            tags = listOf("cloud bitcoin", "бюджет", "cloud-bitcoin", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:clover",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_clover,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Clover",
            labelEn = "Clover",
            tags = listOf("clover", "питомец", "животные", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:clover-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_clover_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Clover 2",
            labelEn = "Clover 2",
            tags = listOf("clover-2", "питомец", "животные", "зелень", "природа", "nature", "растения", "clover 2")
        ),
        IconEntry(
            key = "tabler:coffee",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coffee,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Coffee",
            labelEn = "Coffee",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "coffee", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:coin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin",
            labelEn = "Coin",
            tags = listOf("бюджет", "coin", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Bitcoin",
            labelEn = "Coin Bitcoin",
            tags = listOf("бюджет", "coin-bitcoin", "доход", "деньги", "финансы", "coin bitcoin", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-euro",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_euro,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Euro",
            labelEn = "Coin Euro",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "coin-euro", "finance", "money", "копить", "coin euro")
        ),
        IconEntry(
            key = "tabler:coin-monero",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_monero,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Monero",
            labelEn = "Coin Monero",
            tags = listOf("бюджет", "coin monero", "доход", "деньги", "финансы", "coin-monero", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-pound",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_pound,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Pound",
            labelEn = "Coin Pound",
            tags = listOf("бюджет", "coin pound", "coin-pound", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-rupee",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_rupee,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Rupee",
            labelEn = "Coin Rupee",
            tags = listOf("coin-rupee", "бюджет", "coin rupee", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-taka",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_taka,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Taka",
            labelEn = "Coin Taka",
            tags = listOf("бюджет", "coin taka", "доход", "деньги", "финансы", "coin-taka", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-yen",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_yen,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Yen",
            labelEn = "Coin Yen",
            tags = listOf("бюджет", "coin yen", "доход", "деньги", "финансы", "finance", "coin-yen", "money", "копить")
        ),
        IconEntry(
            key = "tabler:coin-yuan",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coin_yuan,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coin Yuan",
            labelEn = "Coin Yuan",
            tags = listOf("бюджет", "coin yuan", "доход", "деньги", "финансы", "money", "finance", "coin-yuan", "копить")
        ),
        IconEntry(
            key = "tabler:coins",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_coins,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Coins",
            labelEn = "Coins",
            tags = listOf("бюджет", "coins", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cooker",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cooker,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Cooker",
            labelEn = "Cooker",
            tags = listOf("питание", "cooker", "кухня", "диета", "кушать", "food", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:cookie",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cookie,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Cookie",
            labelEn = "Cookie",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "еда", "nutrition", "cookie")
        ),
        IconEntry(
            key = "tabler:cookie-man",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cookie_man,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Cookie Man",
            labelEn = "Cookie Man",
            tags = listOf("питание", "кухня", "диета", "кушать", "cookie man", "food", "cookie-man", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:credit-card",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_credit_card,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card",
            labelEn = "Credit Card",
            tags = listOf("бюджет", "credit-card", "credit card", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:credit-card-hand",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_credit_card_hand,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Hand",
            labelEn = "Credit Card Hand",
            tags = listOf("бюджет", "credit card hand", "credit-card-hand", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:credit-card-pay",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_credit_card_pay,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Pay",
            labelEn = "Credit Card Pay",
            tags = listOf("бюджет", "credit card pay", "доход", "деньги", "финансы", "finance", "money", "копить", "credit-card-pay")
        ),
        IconEntry(
            key = "tabler:credit-card-refund",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_credit_card_refund,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Credit Card Refund",
            labelEn = "Credit Card Refund",
            tags = listOf("бюджет", "credit-card-refund", "credit card refund", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:cup",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_cup,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Cup",
            labelEn = "Cup",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "cup", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:currency-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_currency_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Currency Bitcoin",
            labelEn = "Currency Bitcoin",
            tags = listOf("бюджет", "currency-bitcoin", "доход", "деньги", "финансы", "finance", "money", "копить", "currency bitcoin")
        ),
        IconEntry(
            key = "tabler:currency-dogecoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_currency_dogecoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Currency Dogecoin",
            labelEn = "Currency Dogecoin",
            tags = listOf("бюджет", "currency-dogecoin", "доход", "деньги", "финансы", "finance", "currency dogecoin", "money", "копить")
        ),
        IconEntry(
            key = "tabler:currency-dollar-brunei",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_currency_dollar_brunei,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Currency Dollar Brunei",
            labelEn = "Currency Dollar Brunei",
            tags = listOf("тренировка", "упражнения", "currency-dollar-brunei", "спорт", "fitness", "активность", "workout", "currency dollar brunei", "зал")
        ),
        IconEntry(
            key = "tabler:currency-litecoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_currency_litecoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Currency Litecoin",
            labelEn = "Currency Litecoin",
            tags = listOf("бюджет", "currency-litecoin", "finance", "доход", "деньги", "финансы", "currency litecoin", "money", "копить")
        ),
        IconEntry(
            key = "tabler:currency-zcash",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_currency_zcash,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Currency Zcash",
            labelEn = "Currency Zcash",
            tags = listOf("бюджет", "currency zcash", "currency-zcash", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:current-location",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_current_location,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Current Location",
            labelEn = "Current Location",
            tags = listOf("питомец", "животные", "current-location", "природа", "nature", "растения", "current location", "зелень")
        ),
        IconEntry(
            key = "tabler:desk",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_desk,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Desk",
            labelEn = "Desk",
            tags = listOf("clean", "дом", "desk", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-3d-camera",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_3d_camera,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Device 3D Camera",
            labelEn = "Device 3D Camera",
            tags = listOf("device 3d camera", "музыка", "music", "device-3d-camera", "арт", "хобби", "art", "творчество", "рисовать")
        ),
        IconEntry(
            key = "tabler:device-camera-phone",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_camera_phone,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Device Camera Phone",
            labelEn = "Device Camera Phone",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "device-camera-phone", "рисовать", "device camera phone")
        ),
        IconEntry(
            key = "tabler:device-computer-camera",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_computer_camera,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Device Computer Camera",
            labelEn = "Device Computer Camera",
            tags = listOf("device computer camera", "музыка", "music", "арт", "хобби", "art", "творчество", "рисовать", "device-computer-camera")
        ),
        IconEntry(
            key = "tabler:device-computer-camera-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_computer_camera_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Device Computer Camera 2",
            labelEn = "Device Computer Camera 2",
            tags = listOf("музыка", "music", "арт", "хобби", "device-computer-camera-2", "art", "творчество", "рисовать", "device computer camera 2")
        ),
        IconEntry(
            key = "tabler:device-desktop",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop",
            labelEn = "Device Desktop",
            tags = listOf("clean", "device-desktop", "дом", "чистота", "порядок", "device desktop", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-analytics",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_analytics,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Analytics",
            labelEn = "Device Desktop Analytics",
            tags = listOf("clean", "device desktop analytics", "дом", "чистота", "порядок", "быт", "уборка", "device-desktop-analytics", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-bolt",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_bolt,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Bolt",
            labelEn = "Device Desktop Bolt",
            tags = listOf("clean", "device-desktop-bolt", "дом", "чистота", "порядок", "быт", "device desktop bolt", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-cancel",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_cancel,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Cancel",
            labelEn = "Device Desktop Cancel",
            tags = listOf("device desktop cancel", "clean", "дом", "чистота", "порядок", "быт", "device-desktop-cancel", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_check,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Check",
            labelEn = "Device Desktop Check",
            tags = listOf("device-desktop-check", "clean", "device desktop check", "дом", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-code",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_code,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Code",
            labelEn = "Device Desktop Code",
            tags = listOf("clean", "device desktop code", "дом", "device-desktop-code", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_cog,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Cog",
            labelEn = "Device Desktop Cog",
            tags = listOf("clean", "device-desktop-cog", "дом", "чистота", "порядок", "быт", "уборка", "device desktop cog", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-dollar",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_dollar,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Dollar",
            labelEn = "Device Desktop Dollar",
            tags = listOf("device-desktop-dollar", "clean", "дом", "device desktop dollar", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_down,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Down",
            labelEn = "Device Desktop Down",
            tags = listOf("device desktop down", "clean", "дом", "чистота", "порядок", "быт", "уборка", "device-desktop-down", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-exclamation",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_exclamation,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Exclamation",
            labelEn = "Device Desktop Exclamation",
            tags = listOf("clean", "device desktop exclamation", "дом", "home", "чистота", "порядок", "быт", "уборка", "device-desktop-exclamation")
        ),
        IconEntry(
            key = "tabler:device-desktop-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_heart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Heart",
            labelEn = "Device Desktop Heart",
            tags = listOf("clean", "дом", "device desktop heart", "чистота", "порядок", "быт", "уборка", "device-desktop-heart", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Minus",
            labelEn = "Device Desktop Minus",
            tags = listOf("clean", "дом", "device desktop minus", "чистота", "порядок", "быт", "уборка", "device-desktop-minus", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-pause",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_pause,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Pause",
            labelEn = "Device Desktop Pause",
            tags = listOf("clean", "дом", "чистота", "порядок", "быт", "уборка", "device desktop pause", "device-desktop-pause", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-pin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_pin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Pin",
            labelEn = "Device Desktop Pin",
            tags = listOf("device desktop pin", "clean", "дом", "чистота", "порядок", "device-desktop-pin", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Plus",
            labelEn = "Device Desktop Plus",
            tags = listOf("clean", "дом", "чистота", "device desktop plus", "быт", "порядок", "уборка", "device-desktop-plus", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-question",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_question,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Question",
            labelEn = "Device Desktop Question",
            tags = listOf("device desktop question", "clean", "дом", "device-desktop-question", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_search,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Search",
            labelEn = "Device Desktop Search",
            tags = listOf("clean", "дом", "device-desktop-search", "чистота", "быт", "device desktop search", "уборка", "порядок", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-share",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_share,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Share",
            labelEn = "Device Desktop Share",
            tags = listOf("clean", "дом", "device desktop share", "device-desktop-share", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-star",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_star,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Star",
            labelEn = "Device Desktop Star",
            tags = listOf("clean", "device-desktop-star", "дом", "device desktop star", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_up,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop Up",
            labelEn = "Device Desktop Up",
            tags = listOf("clean", "дом", "чистота", "порядок", "быт", "уборка", "device-desktop-up", "device desktop up", "home")
        ),
        IconEntry(
            key = "tabler:device-desktop-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_desktop_x,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Device Desktop X",
            labelEn = "Device Desktop X",
            tags = listOf("clean", "device desktop x", "дом", "device-desktop-x", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:device-gamepad",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_gamepad,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Device Gamepad",
            labelEn = "Device Gamepad",
            tags = listOf("device gamepad", "device-gamepad")
        ),
        IconEntry(
            key = "tabler:device-gamepad-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_gamepad_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Device Gamepad 2",
            labelEn = "Device Gamepad 2",
            tags = listOf("device-gamepad-2", "device gamepad 2")
        ),
        IconEntry(
            key = "tabler:device-gamepad-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_gamepad_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Device Gamepad 3",
            labelEn = "Device Gamepad 3",
            tags = listOf("device gamepad 3", "device-gamepad-3")
        ),
        IconEntry(
            key = "tabler:device-sd-card",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_device_sd_card,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Device Sd Card",
            labelEn = "Device Sd Card",
            tags = listOf("бюджет", "device sd card", "device-sd-card", "деньги", "доход", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:diamond",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_diamond,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Diamond",
            labelEn = "Diamond",
            tags = listOf("бюджет", "доход", "diamond", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:diamonds",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_diamonds,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Diamonds",
            labelEn = "Diamonds",
            tags = listOf("бюджет", "diamonds", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:dice",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice",
            labelEn = "Dice",
            tags = listOf("dice")
        ),
        IconEntry(
            key = "tabler:dice-1",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice_1,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 1",
            labelEn = "Dice 1",
            tags = listOf("dice 1", "dice-1")
        ),
        IconEntry(
            key = "tabler:dice-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 2",
            labelEn = "Dice 2",
            tags = listOf("dice-2", "dice 2")
        ),
        IconEntry(
            key = "tabler:dice-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 3",
            labelEn = "Dice 3",
            tags = listOf("dice-3", "dice 3")
        ),
        IconEntry(
            key = "tabler:dice-4",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice_4,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 4",
            labelEn = "Dice 4",
            tags = listOf("dice-4", "dice 4")
        ),
        IconEntry(
            key = "tabler:dice-5",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice_5,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 5",
            labelEn = "Dice 5",
            tags = listOf("dice-5", "dice 5")
        ),
        IconEntry(
            key = "tabler:dice-6",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dice_6,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Dice 6",
            labelEn = "Dice 6",
            tags = listOf("dice 6", "dice-6")
        ),
        IconEntry(
            key = "tabler:dna",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dna,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Dna",
            labelEn = "Dna",
            tags = listOf("знания", "learn", "учеба", "наука", "study", "dna", "книги", "учиться")
        ),
        IconEntry(
            key = "tabler:dna-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dna_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.STUDY,
            labelRu = "Dna 2",
            labelEn = "Dna 2",
            tags = listOf("знания", "learn", "dna-2", "учеба", "наука", "study", "dna 2", "книги", "учиться")
        ),
    )

    private fun getPart13(): List<IconEntry> = listOf(
        IconEntry(
            key = "tabler:dog",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dog,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Dog",
            labelEn = "Dog",
            tags = listOf("питомец", "животные", "природа", "dog", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:dog-bowl",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_dog_bowl,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Dog Bowl",
            labelEn = "Dog Bowl",
            tags = listOf("питомец", "животные", "dog bowl", "природа", "nature", "dog-bowl", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:door",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_door,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Door",
            labelEn = "Door",
            tags = listOf("clean", "дом", "door", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:door-enter",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_door_enter,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Door Enter",
            labelEn = "Door Enter",
            tags = listOf("clean", "дом", "door-enter", "чистота", "порядок", "быт", "уборка", "door enter", "home")
        ),
        IconEntry(
            key = "tabler:door-exit",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_door_exit,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Door Exit",
            labelEn = "Door Exit",
            tags = listOf("clean", "дом", "door-exit", "чистота", "порядок", "быт", "уборка", "door exit", "home")
        ),
        IconEntry(
            key = "tabler:door-hanger",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_door_hanger,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Door Hanger",
            labelEn = "Door Hanger",
            tags = listOf("clean", "дом", "door hanger", "чистота", "порядок", "быт", "уборка", "door-hanger", "home")
        ),
        IconEntry(
            key = "tabler:duplicate",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_duplicate,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Duplicate",
            labelEn = "Duplicate",
            tags = listOf("питомец", "животные", "природа", "nature", "растения", "зелень", "duplicate")
        ),
        IconEntry(
            key = "tabler:e-passport",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_e_passport,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "E Passport",
            labelEn = "E Passport",
            tags = listOf("тренировка", "упражнения", "e-passport", "fitness", "спорт", "активность", "workout", "зал", "e passport")
        ),
        IconEntry(
            key = "tabler:egg",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_egg,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Egg",
            labelEn = "Egg",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "egg", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:egg-cracked",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_egg_cracked,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Egg Cracked",
            labelEn = "Egg Cracked",
            tags = listOf("питание", "кухня", "диета", "egg-cracked", "кушать", "food", "egg cracked", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:egg-fried",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_egg_fried,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Egg Fried",
            labelEn = "Egg Fried",
            tags = listOf("питание", "egg-fried", "кухня", "диета", "кушать", "food", "egg fried", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:eggs",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_eggs,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Eggs",
            labelEn = "Eggs",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "eggs", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:emergency-bed",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_emergency_bed,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SLEEP,
            labelRu = "Emergency Bed",
            labelEn = "Emergency Bed",
            tags = listOf("rest", "спать", "emergency bed", "sleep", "отдых", "сон", "релакс", "emergency-bed", "ночь")
        ),
        IconEntry(
            key = "tabler:eye-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_eye_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Eye Bitcoin",
            labelEn = "Eye Bitcoin",
            tags = listOf("бюджет", "доход", "деньги", "eye-bitcoin", "eye bitcoin", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:eyeglass",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_eyeglass,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Eyeglass",
            labelEn = "Eyeglass",
            tags = listOf("eyeglass", "питание", "кухня", "диета", "кушать", "food", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:eyeglass-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_eyeglass_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Eyeglass 2",
            labelEn = "Eyeglass 2",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "eyeglass 2", "еда", "eyeglass-2", "nutrition")
        ),
        IconEntry(
            key = "tabler:file-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "File Bitcoin",
            labelEn = "File Bitcoin",
            tags = listOf("бюджет", "file bitcoin", "доход", "деньги", "финансы", "file-bitcoin", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:file-certificate",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_certificate,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "File Certificate",
            labelEn = "File Certificate",
            tags = listOf("file certificate", "питомец", "file-certificate", "животные", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:file-chart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_chart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "File Chart",
            labelEn = "File Chart",
            tags = listOf("бюджет", "file-chart", "доход", "деньги", "финансы", "finance", "file chart", "money", "копить")
        ),
        IconEntry(
            key = "tabler:file-music",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_music,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "File Music",
            labelEn = "File Music",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "file-music", "рисовать", "file music")
        ),
        IconEntry(
            key = "tabler:file-pencil",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_pencil,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "File Pencil",
            labelEn = "File Pencil",
            tags = listOf("file-pencil", "file pencil", "музыка", "music", "арт", "хобби", "art", "творчество", "рисовать")
        ),
        IconEntry(
            key = "tabler:file-report",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_report,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "File Report",
            labelEn = "File Report",
            tags = listOf("бюджет", "file report", "доход", "деньги", "file-report", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:file-scissors",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_file_scissors,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "File Scissors",
            labelEn = "File Scissors",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "file-scissors", "рисовать", "file scissors")
        ),
        IconEntry(
            key = "tabler:fish",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_fish,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Fish",
            labelEn = "Fish",
            tags = listOf("питомец", "животные", "fish", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:fish-bone",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_fish_bone,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Fish Bone",
            labelEn = "Fish Bone",
            tags = listOf("питомец", "животные", "fish bone", "природа", "fish-bone", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:fish-christianity",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_fish_christianity,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Fish Christianity",
            labelEn = "Fish Christianity",
            tags = listOf("питомец", "животные", "fish-christianity", "природа", "nature", "fish christianity", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:fish-hook",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_fish_hook,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Fish Hook",
            labelEn = "Fish Hook",
            tags = listOf("питомец", "животные", "fish hook", "природа", "nature", "растения", "зелень", "fish-hook")
        ),
        IconEntry(
            key = "tabler:flag-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_flag_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Flag Bitcoin",
            labelEn = "Flag Bitcoin",
            tags = listOf("бюджет", "flag bitcoin", "flag-bitcoin", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:flower",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_flower,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Flower",
            labelEn = "Flower",
            tags = listOf("питомец", "животные", "зелень", "природа", "nature", "flower", "растения")
        ),
        IconEntry(
            key = "tabler:garden-cart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_garden_cart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Garden Cart",
            labelEn = "Garden Cart",
            tags = listOf("дорога", "travel", "garden cart", "путешествия", "отпуск", "поездка", "garden-cart", "транспорт")
        ),
        IconEntry(
            key = "tabler:gender-hermaphrodite",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_gender_hermaphrodite,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Gender Hermaphrodite",
            labelEn = "Gender Hermaphrodite",
            tags = listOf("gender hermaphrodite", "дорога", "travel", "путешествия", "отпуск", "поездка", "gender-hermaphrodite", "транспорт")
        ),
        IconEntry(
            key = "tabler:gift-card",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_gift_card,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Gift Card",
            labelEn = "Gift Card",
            tags = listOf("бюджет", "gift-card", "доход", "деньги", "финансы", "finance", "money", "копить", "gift card")
        ),
        IconEntry(
            key = "tabler:glass",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_glass,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Glass",
            labelEn = "Glass",
            tags = listOf("питание", "glass", "кухня", "диета", "кушать", "food", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:glass-champagne",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_glass_champagne,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Glass Champagne",
            labelEn = "Glass Champagne",
            tags = listOf("питание", "glass champagne", "кухня", "диета", "кушать", "food", "glass-champagne", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:glass-cocktail",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_glass_cocktail,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Glass Cocktail",
            labelEn = "Glass Cocktail",
            tags = listOf("питание", "glass-cocktail", "кухня", "диета", "кушать", "food", "еда", "glass cocktail", "nutrition")
        ),
        IconEntry(
            key = "tabler:glass-full",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_glass_full,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Glass Full",
            labelEn = "Glass Full",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "glass-full", "еда", "glass full", "nutrition")
        ),
        IconEntry(
            key = "tabler:glass-gin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_glass_gin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Glass Gin",
            labelEn = "Glass Gin",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "glass gin", "еда", "glass-gin", "nutrition")
        ),
        IconEntry(
            key = "tabler:go-game",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_go_game,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Go Game",
            labelEn = "Go Game",
            tags = listOf("go game", "go-game")
        ),
        IconEntry(
            key = "tabler:guitar-pick",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_guitar_pick,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Guitar Pick",
            labelEn = "Guitar Pick",
            tags = listOf("музыка", "music", "арт", "guitar pick", "хобби", "art", "творчество", "рисовать", "guitar-pick")
        ),
        IconEntry(
            key = "tabler:gymnastics",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_gymnastics,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Gymnastics",
            labelEn = "Gymnastics",
            tags = listOf("тренировка", "упражнения", "fitness", "спорт", "активность", "workout", "зал", "gymnastics")
        ),
        IconEntry(
            key = "tabler:haze-moon",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_haze_moon,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SLEEP,
            labelRu = "Haze Moon",
            labelEn = "Haze Moon",
            tags = listOf("rest", "спать", "haze-moon", "sleep", "отдых", "haze moon", "сон", "релакс", "ночь")
        ),
        IconEntry(
            key = "tabler:heart-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_heart_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Heart Bitcoin",
            labelEn = "Heart Bitcoin",
            tags = listOf("бюджет", "heart-bitcoin", "доход", "деньги", "heart bitcoin", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:home-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_home_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Home Bitcoin",
            labelEn = "Home Bitcoin",
            tags = listOf("бюджет", "home bitcoin", "home-bitcoin", "деньги", "доход", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:hourglass",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_hourglass,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Hourglass",
            labelEn = "Hourglass",
            tags = listOf("питание", "кухня", "диета", "кушать", "hourglass", "food", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:hourglass-empty",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_hourglass_empty,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Hourglass Empty",
            labelEn = "Hourglass Empty",
            tags = listOf("питание", "кухня", "диета", "hourglass-empty", "кушать", "food", "еда", "hourglass empty", "nutrition")
        ),
        IconEntry(
            key = "tabler:hourglass-high",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_hourglass_high,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Hourglass High",
            labelEn = "Hourglass High",
            tags = listOf("питание", "кухня", "hourglass high", "диета", "кушать", "food", "hourglass-high", "еда", "nutrition")
        ),
        IconEntry(
            key = "tabler:hourglass-low",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_hourglass_low,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Hourglass Low",
            labelEn = "Hourglass Low",
            tags = listOf("питание", "кухня", "диета", "кушать", "food", "hourglass low", "еда", "hourglass-low", "nutrition")
        ),
        IconEntry(
            key = "tabler:ice-skating",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ice_skating,
            namespace = IconNamespace.TABLER,
            category = IconCategory.SPORT,
            labelRu = "Ice Skating",
            labelEn = "Ice Skating",
            tags = listOf("ice skating", "тренировка", "упражнения", "fitness", "спорт", "активность", "workout", "ice-skating", "зал")
        ),
        IconEntry(
            key = "tabler:ironing",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ironing,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Ironing",
            labelEn = "Ironing",
            tags = listOf("clean", "ironing", "дом", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:ironing-1",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ironing_1,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Ironing 1",
            labelEn = "Ironing 1",
            tags = listOf("ironing-1", "clean", "дом", "чистота", "порядок", "быт", "уборка", "ironing 1", "home")
        ),
        IconEntry(
            key = "tabler:ironing-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ironing_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Ironing 2",
            labelEn = "Ironing 2",
            tags = listOf("clean", "ironing-2", "дом", "ironing 2", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:ironing-3",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ironing_3,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Ironing 3",
            labelEn = "Ironing 3",
            tags = listOf("clean", "ironing 3", "дом", "чистота", "порядок", "быт", "уборка", "ironing-3", "home")
        ),
        IconEntry(
            key = "tabler:ironing-steam",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_ironing_steam,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FOOD,
            labelRu = "Ironing Steam",
            labelEn = "Ironing Steam",
            tags = listOf("питание", "ironing steam", "кухня", "диета", "кушать", "food", "еда", "ironing-steam", "nutrition")
        ),
        IconEntry(
            key = "tabler:lamp",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_lamp,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Lamp",
            labelEn = "Lamp",
            tags = listOf("clean", "lamp", "дом", "чистота", "порядок", "быт", "уборка", "home")
        ),
        IconEntry(
            key = "tabler:lamp-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_lamp_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOME,
            labelRu = "Lamp 2",
            labelEn = "Lamp 2",
            tags = listOf("clean", "дом", "чистота", "порядок", "быт", "уборка", "lamp 2", "lamp-2", "home")
        ),
        IconEntry(
            key = "tabler:layout-cards",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_layout_cards,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Layout Cards",
            labelEn = "Layout Cards",
            tags = listOf("бюджет", "layout cards", "layout-cards", "доход", "деньги", "финансы", "finance", "money", "копить")
        ),
        IconEntry(
            key = "tabler:leaf",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_leaf,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Leaf",
            labelEn = "Leaf",
            tags = listOf("питомец", "животные", "leaf", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:leaf-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_leaf_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Leaf 2",
            labelEn = "Leaf 2",
            tags = listOf("питомец", "животные", "leaf-2", "природа", "nature", "leaf 2", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:leaf-maple",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_leaf_maple,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Leaf Maple",
            labelEn = "Leaf Maple",
            tags = listOf("питомец", "животные", "leaf maple", "природа", "nature", "растения", "зелень", "leaf-maple")
        ),
        IconEntry(
            key = "tabler:lego",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_lego,
            namespace = IconNamespace.TABLER,
            category = IconCategory.HOBBIES,
            labelRu = "Lego",
            labelEn = "Lego",
            tags = listOf("lego")
        ),
        IconEntry(
            key = "tabler:library-photo",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_library_photo,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Library Photo",
            labelEn = "Library Photo",
            tags = listOf("музыка", "music", "арт", "хобби", "art", "творчество", "library-photo", "library photo", "рисовать")
        ),
        IconEntry(
            key = "tabler:list-tree",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_list_tree,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "List Tree",
            labelEn = "List Tree",
            tags = listOf("питомец", "животные", "зелень", "природа", "nature", "растения", "list tree", "list-tree")
        ),
        IconEntry(
            key = "tabler:live-photo",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_live_photo,
            namespace = IconNamespace.TABLER,
            category = IconCategory.ART,
            labelRu = "Live Photo",
            labelEn = "Live Photo",
            tags = listOf("музыка", "music", "live-photo", "арт", "хобби", "art", "творчество", "live photo", "рисовать")
        ),
        IconEntry(
            key = "tabler:location",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location",
            labelEn = "Location",
            tags = listOf("location", "питомец", "животные", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-bolt",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_bolt,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Bolt",
            labelEn = "Location Bolt",
            tags = listOf("питомец", "животные", "зелень", "природа", "location bolt", "nature", "location-bolt", "растения")
        ),
        IconEntry(
            key = "tabler:location-broken",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_broken,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Broken",
            labelEn = "Location Broken",
            tags = listOf("location-broken", "питомец", "животные", "природа", "location broken", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-cancel",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_cancel,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Cancel",
            labelEn = "Location Cancel",
            tags = listOf("питомец", "животные", "location cancel", "location-cancel", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_check,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Check",
            labelEn = "Location Check",
            tags = listOf("питомец", "животные", "природа", "location check", "nature", "location-check", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-code",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_code,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Code",
            labelEn = "Location Code",
            tags = listOf("location code", "питомец", "животные", "природа", "nature", "растения", "зелень", "location-code")
        ),
        IconEntry(
            key = "tabler:location-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_cog,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Cog",
            labelEn = "Location Cog",
            tags = listOf("питомец", "животные", "location cog", "природа", "location-cog", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-discount",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_discount,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Discount",
            labelEn = "Location Discount",
            tags = listOf("питомец", "животные", "природа", "location-discount", "location discount", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-dollar",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_dollar,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Dollar",
            labelEn = "Location Dollar",
            tags = listOf("питомец", "животные", "природа", "location-dollar", "nature", "растения", "location dollar", "зелень")
        ),
        IconEntry(
            key = "tabler:location-down",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_down,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Down",
            labelEn = "Location Down",
            tags = listOf("питомец", "животные", "location down", "location-down", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-exclamation",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_exclamation,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Exclamation",
            labelEn = "Location Exclamation",
            tags = listOf("питомец", "животные", "природа", "nature", "location-exclamation", "растения", "зелень", "location exclamation")
        ),
        IconEntry(
            key = "tabler:location-heart",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_heart,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Heart",
            labelEn = "Location Heart",
            tags = listOf("питомец", "животные", "location-heart", "природа", "nature", "location heart", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-minus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_minus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Minus",
            labelEn = "Location Minus",
            tags = listOf("питомец", "животные", "location-minus", "location minus", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-pause",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_pause,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Pause",
            labelEn = "Location Pause",
            tags = listOf("location-pause", "питомец", "животные", "природа", "nature", "location pause", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-pin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_pin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Pin",
            labelEn = "Location Pin",
            tags = listOf("location-pin", "питомец", "животные", "location pin", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-plus",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_plus,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Plus",
            labelEn = "Location Plus",
            tags = listOf("питомец", "животные", "location-plus", "природа", "location plus", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-question",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_question,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Question",
            labelEn = "Location Question",
            tags = listOf("питомец", "животные", "location-question", "природа", "nature", "location question", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-search",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_search,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Search",
            labelEn = "Location Search",
            tags = listOf("питомец", "животные", "location search", "природа", "location-search", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-share",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_share,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Share",
            labelEn = "Location Share",
            tags = listOf("питомец", "животные", "природа", "nature", "location share", "растения", "зелень", "location-share")
        ),
        IconEntry(
            key = "tabler:location-star",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_star,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Star",
            labelEn = "Location Star",
            tags = listOf("location-star", "питомец", "животные", "location star", "природа", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-up",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_up,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location Up",
            labelEn = "Location Up",
            tags = listOf("location-up", "питомец", "животные", "природа", "location up", "nature", "растения", "зелень")
        ),
        IconEntry(
            key = "tabler:location-x",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_location_x,
            namespace = IconNamespace.TABLER,
            category = IconCategory.NATURE,
            labelRu = "Location X",
            labelEn = "Location X",
            tags = listOf("питомец", "животные", "природа", "nature", "location x", "растения", "зелень", "location-x")
        ),
        IconEntry(
            key = "tabler:lock-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_lock_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Lock Bitcoin",
            labelEn = "Lock Bitcoin",
            tags = listOf("бюджет", "доход", "деньги", "финансы", "money", "finance", "lock-bitcoin", "lock bitcoin", "копить")
        ),
        IconEntry(
            key = "tabler:mail-bitcoin",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_mail_bitcoin,
            namespace = IconNamespace.TABLER,
            category = IconCategory.FINANCE,
            labelRu = "Mail Bitcoin",
            labelEn = "Mail Bitcoin",
            tags = listOf("mail-bitcoin", "бюджет", "доход", "деньги", "финансы", "finance", "money", "копить", "mail bitcoin")
        ),
        IconEntry(
            key = "tabler:map",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map",
            labelEn = "Map",
            tags = listOf("map", "дорога", "travel", "путешествия", "отпуск", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-2",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_2,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map 2",
            labelEn = "Map 2",
            tags = listOf("дорога", "travel", "map-2", "путешествия", "отпуск", "поездка", "map 2", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-bolt",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_bolt,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Bolt",
            labelEn = "Map Bolt",
            tags = listOf("map bolt", "дорога", "travel", "путешествия", "отпуск", "поездка", "map-bolt", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-cancel",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_cancel,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Cancel",
            labelEn = "Map Cancel",
            tags = listOf("дорога", "travel", "путешествия", "отпуск", "map cancel", "map-cancel", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-check",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_check,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Check",
            labelEn = "Map Check",
            tags = listOf("дорога", "map-check", "travel", "путешествия", "отпуск", "map check", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-code",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_code,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Code",
            labelEn = "Map Code",
            tags = listOf("дорога", "travel", "map code", "путешествия", "отпуск", "map-code", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-cog",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_cog,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Cog",
            labelEn = "Map Cog",
            tags = listOf("map-cog", "дорога", "travel", "путешествия", "отпуск", "map cog", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-discount",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_discount,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Discount",
            labelEn = "Map Discount",
            tags = listOf("дорога", "map discount", "travel", "путешествия", "отпуск", "map-discount", "поездка", "транспорт")
        ),
        IconEntry(
            key = "tabler:map-dollar",
            legacyKey = null,
            drawableRes = R.drawable.ic_t_map_dollar,
            namespace = IconNamespace.TABLER,
            category = IconCategory.TRAVEL,
            labelRu = "Map Dollar",
            labelEn = "Map Dollar",
            tags = listOf("дорога", "travel", "путешествия", "отпуск", "map dollar", "map-dollar", "поездка", "транспорт")
        ),
    )

}
