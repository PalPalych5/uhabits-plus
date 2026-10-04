/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.common.icons

import androidx.annotation.DrawableRes
import org.isoron.uhabits.R

enum class IconCategory(val titleRu: String, val titleEn: String) {
    ALL("Все", "All"),
    STUDY("Учёба", "Study"),
    WORK("Работа", "Work"),
    CODE("Код", "Code"),
    HEALTH("Здоровье", "Health"),
    SPORT("Спорт", "Sport"),
    ROUTINE("Режим", "Routine"),
    FOOD("Еда", "Food"),
    SLEEP("Сон", "Sleep"),
    HOME("Дом", "Home"),
    PEOPLE("Люди", "People"),
    FINANCE("Финансы", "Finance"),
    ART("Творчество", "Art"),
    TRAVEL("Путешествия", "Travel"),
    MIND("Развитие", "Mind")
}

data class IconEntry(
    val key: String,
    @DrawableRes val drawableRes: Int,
    val category: IconCategory,
    val labelRu: String,
    val labelEn: String,
    val tags: List<String>
)

object IconPack {

    val DEFAULT_CONTAINER_ICON_RES: Int = R.drawable.ic_cat_folder
    val DEFAULT_HABIT_ICON_RES: Int = R.drawable.ic_cat_bookmark

    val CURATED_EMOJIS: List<String> = listOf(
        "🎓", "📚", "💻", "🧠", "💪", "🏃", "🏋️", "🧘",
        "🥗", "🍎", "☕", "🌙", "☀️", "⏰", "🏠", "💼",
        "💰", "🎨", "🎵", "✈️", "🌱", "🎯", "⭐", "🔥",
        "💧", "❤️", "🛡️", "💬", "🚗", "🚲", "⚽", "🏀",
        "🎮", "🎸", "🎹", "📷", "🎁", "💡", "🔑", "🚀"
    )

    val ICONS: List<IconEntry> = listOf(
        IconEntry(
            key = "book",
            drawableRes = R.drawable.ic_cat_book,
            category = IconCategory.STUDY,
            labelRu = "Книга",
            labelEn = "Book",
            tags = listOf("читать", "reading", "учеба", "литература")
        ),        IconEntry(
            key = "book-open",
            drawableRes = R.drawable.ic_cat_book_open,
            category = IconCategory.STUDY,
            labelRu = "Открытая книга",
            labelEn = "Open Book",
            tags = listOf("учеба", "конспект", "чтение")
        ),        IconEntry(
            key = "bookmark",
            drawableRes = R.drawable.ic_cat_bookmark,
            category = IconCategory.STUDY,
            labelRu = "Закладка",
            labelEn = "Bookmark",
            tags = listOf("заметка", "важное")
        ),        IconEntry(
            key = "graduation-cap",
            drawableRes = R.drawable.ic_cat_graduation_cap,
            category = IconCategory.STUDY,
            labelRu = "Выпускной / Академия",
            labelEn = "Graduation Cap",
            tags = listOf("университет", "вуз", "диплом", "курсы")
        ),        IconEntry(
            key = "school",
            drawableRes = R.drawable.ic_cat_school,
            category = IconCategory.STUDY,
            labelRu = "Школа / Вуз",
            labelEn = "School",
            tags = listOf("учеба", "здание", "образование")
        ),        IconEntry(
            key = "pencil",
            drawableRes = R.drawable.ic_cat_pencil,
            category = IconCategory.STUDY,
            labelRu = "Карандаш",
            labelEn = "Pencil",
            tags = listOf("писать", "запись", "черновик")
        ),        IconEntry(
            key = "pen-tool",
            drawableRes = R.drawable.ic_cat_pen_tool,
            category = IconCategory.STUDY,
            labelRu = "Перо",
            labelEn = "Pen Tool",
            tags = listOf("дизайн", "письмо", "каллиграфия")
        ),        IconEntry(
            key = "calculator",
            drawableRes = R.drawable.ic_cat_calculator,
            category = IconCategory.STUDY,
            labelRu = "Калькулятор",
            labelEn = "Calculator",
            tags = listOf("математика", "расчеты", "счет")
        ),        IconEntry(
            key = "library",
            drawableRes = R.drawable.ic_cat_library,
            category = IconCategory.STUDY,
            labelRu = "Библиотека",
            labelEn = "Library",
            tags = listOf("книги", "архив", "знания")
        ),        IconEntry(
            key = "microscope",
            drawableRes = R.drawable.ic_cat_microscope,
            category = IconCategory.STUDY,
            labelRu = "Микроскоп",
            labelEn = "Microscope",
            tags = listOf("наука", "исследования", "биология")
        ),        IconEntry(
            key = "flask-conical",
            drawableRes = R.drawable.ic_cat_flask_conical,
            category = IconCategory.STUDY,
            labelRu = "Колба",
            labelEn = "Flask",
            tags = listOf("химия", "опыты", "лаборатория")
        ),        IconEntry(
            key = "atom",
            drawableRes = R.drawable.ic_cat_atom,
            category = IconCategory.STUDY,
            labelRu = "Атом",
            labelEn = "Atom",
            tags = listOf("физика", "наука", "исследования")
        ),        IconEntry(
            key = "languages",
            drawableRes = R.drawable.ic_cat_languages,
            category = IconCategory.STUDY,
            labelRu = "Языки",
            labelEn = "Languages",
            tags = listOf("перевод", "иностранные", "словарь", "english")
        ),        IconEntry(
            key = "scroll",
            drawableRes = R.drawable.ic_cat_scroll,
            category = IconCategory.STUDY,
            labelRu = "Свиток",
            labelEn = "Scroll",
            tags = listOf("история", "документ", "правила")
        ),        IconEntry(
            key = "file-text",
            drawableRes = R.drawable.ic_cat_file_text,
            category = IconCategory.STUDY,
            labelRu = "Конспект / Статья",
            labelEn = "Text File",
            tags = listOf("документ", "текст", "отчет")
        ),        IconEntry(
            key = "notebook-pen",
            drawableRes = R.drawable.ic_cat_notebook_pen,
            category = IconCategory.STUDY,
            labelRu = "Блокнот с ручкой",
            labelEn = "Notebook",
            tags = listOf("записи", "дневник", "планы")
        ),        IconEntry(
            key = "clipboard-check",
            drawableRes = R.drawable.ic_cat_clipboard_check,
            category = IconCategory.STUDY,
            labelRu = "Чек-лист",
            labelEn = "Clipboard Check",
            tags = listOf("проверка", "задания", "тест")
        ),        IconEntry(
            key = "presentation",
            drawableRes = R.drawable.ic_cat_presentation,
            category = IconCategory.STUDY,
            labelRu = "Презентация",
            labelEn = "Presentation",
            tags = listOf("доклад", "слайды", "выступление")
        ),        IconEntry(
            key = "brain",
            drawableRes = R.drawable.ic_cat_brain,
            category = IconCategory.STUDY,
            labelRu = "Мозг / Память",
            labelEn = "Brain",
            tags = listOf("мышление", "интеллект", "память", "anki")
        ),        IconEntry(
            key = "briefcase",
            drawableRes = R.drawable.ic_cat_briefcase,
            category = IconCategory.WORK,
            labelRu = "Портфель / Работа",
            labelEn = "Briefcase",
            tags = listOf("карьера", "бизнес", "офис")
        ),        IconEntry(
            key = "laptop",
            drawableRes = R.drawable.ic_cat_laptop,
            category = IconCategory.WORK,
            labelRu = "Ноутбук",
            labelEn = "Laptop",
            tags = listOf("компьютер", "работа", "ноут")
        ),        IconEntry(
            key = "monitor",
            drawableRes = R.drawable.ic_cat_monitor,
            category = IconCategory.WORK,
            labelRu = "Монитор",
            labelEn = "Monitor",
            tags = listOf("экран", "рабочее место")
        ),        IconEntry(
            key = "phone",
            drawableRes = R.drawable.ic_cat_phone,
            category = IconCategory.WORK,
            labelRu = "Телефон",
            labelEn = "Phone",
            tags = listOf("звонки", "связь")
        ),        IconEntry(
            key = "mail",
            drawableRes = R.drawable.ic_cat_mail,
            category = IconCategory.WORK,
            labelRu = "Почта",
            labelEn = "Mail",
            tags = listOf("письма", "email", "сообщения")
        ),        IconEntry(
            key = "calendar",
            drawableRes = R.drawable.ic_cat_calendar,
            category = IconCategory.WORK,
            labelRu = "Календарь",
            labelEn = "Calendar",
            tags = listOf("дедлайны", "планирование", "встречи")
        ),        IconEntry(
            key = "calendar-days",
            drawableRes = R.drawable.ic_cat_calendar_days,
            category = IconCategory.WORK,
            labelRu = "Дни календаря",
            labelEn = "Calendar Days",
            tags = listOf("расписание", "дни")
        ),        IconEntry(
            key = "clock",
            drawableRes = R.drawable.ic_cat_clock,
            category = IconCategory.WORK,
            labelRu = "Часы",
            labelEn = "Clock",
            tags = listOf("время", "тайм-менеджмент")
        ),        IconEntry(
            key = "check-square",
            drawableRes = R.drawable.ic_cat_check_square,
            category = IconCategory.WORK,
            labelRu = "Задача",
            labelEn = "Check Square",
            tags = listOf("выполнено", "список")
        ),        IconEntry(
            key = "folder",
            drawableRes = R.drawable.ic_cat_folder,
            category = IconCategory.WORK,
            labelRu = "Папка",
            labelEn = "Folder",
            tags = listOf("файлы", "документы", "проект")
        ),        IconEntry(
            key = "folder-open",
            drawableRes = R.drawable.ic_cat_folder_open,
            category = IconCategory.WORK,
            labelRu = "Открытая папка",
            labelEn = "Open Folder",
            tags = listOf("директория", "файлы")
        ),        IconEntry(
            key = "hard-drive",
            drawableRes = R.drawable.ic_cat_hard_drive,
            category = IconCategory.WORK,
            labelRu = "Диск",
            labelEn = "Hard Drive",
            tags = listOf("хранилище", "архив")
        ),        IconEntry(
            key = "printer",
            drawableRes = R.drawable.ic_cat_printer,
            category = IconCategory.WORK,
            labelRu = "Принтер",
            labelEn = "Printer",
            tags = listOf("печать", "документы")
        ),        IconEntry(
            key = "newspaper",
            drawableRes = R.drawable.ic_cat_newspaper,
            category = IconCategory.WORK,
            labelRu = "Новости / Пресса",
            labelEn = "Newspaper",
            tags = listOf("статьи", "медиа")
        ),        IconEntry(
            key = "badge",
            drawableRes = R.drawable.ic_cat_badge,
            category = IconCategory.WORK,
            labelRu = "Бейдж / Должность",
            labelEn = "Badge",
            tags = listOf("статус", "пропуск")
        ),        IconEntry(
            key = "building-2",
            drawableRes = R.drawable.ic_cat_building_2,
            category = IconCategory.WORK,
            labelRu = "Офис / Здание",
            labelEn = "Building",
            tags = listOf("компания", "корпорация")
        ),        IconEntry(
            key = "compass",
            drawableRes = R.drawable.ic_cat_compass,
            category = IconCategory.WORK,
            labelRu = "Компас / Стратегия",
            labelEn = "Compass",
            tags = listOf("направление", "ориентир")
        ),        IconEntry(
            key = "target",
            drawableRes = R.drawable.ic_cat_target,
            category = IconCategory.WORK,
            labelRu = "Цель",
            labelEn = "Target",
            tags = listOf("мишень", "фокус", "kpi")
        ),        IconEntry(
            key = "code-2",
            drawableRes = R.drawable.ic_cat_code_2,
            category = IconCategory.CODE,
            labelRu = "Исходный код",
            labelEn = "Code",
            tags = listOf("программирование", "скрипт", "разработка")
        ),        IconEntry(
            key = "terminal",
            drawableRes = R.drawable.ic_cat_terminal,
            category = IconCategory.CODE,
            labelRu = "Терминал / Консоль",
            labelEn = "Terminal",
            tags = listOf("командная строка", "bash", "powershell")
        ),        IconEntry(
            key = "database",
            drawableRes = R.drawable.ic_cat_database,
            category = IconCategory.CODE,
            labelRu = "База данных",
            labelEn = "Database",
            tags = listOf("sql", "хранилище", "таблицы")
        ),        IconEntry(
            key = "cpu",
            drawableRes = R.drawable.ic_cat_cpu,
            category = IconCategory.CODE,
            labelRu = "Процессор / Чип",
            labelEn = "CPU",
            tags = listOf("hardware", "железо", "архитектура")
        ),        IconEntry(
            key = "git-branch",
            drawableRes = R.drawable.ic_cat_git_branch,
            category = IconCategory.CODE,
            labelRu = "Git Ветка",
            labelEn = "Git Branch",
            tags = listOf("репозиторий", "ветка", "коммит")
        ),        IconEntry(
            key = "git-commit-horizontal",
            drawableRes = R.drawable.ic_cat_git_commit_horizontal,
            category = IconCategory.CODE,
            labelRu = "Git Коммит",
            labelEn = "Git Commit",
            tags = listOf("фиксация", "изменения")
        ),        IconEntry(
            key = "git-pull-request",
            drawableRes = R.drawable.ic_cat_git_pull_request,
            category = IconCategory.CODE,
            labelRu = "Pull Request",
            labelEn = "Pull Request",
            tags = listOf("мердж", "код-ревью")
        ),        IconEntry(
            key = "server",
            drawableRes = R.drawable.ic_cat_server,
            category = IconCategory.CODE,
            labelRu = "Сервер",
            labelEn = "Server",
            tags = listOf("бэкенд", "хостинг", "инфраструктура")
        ),        IconEntry(
            key = "bug",
            drawableRes = R.drawable.ic_cat_bug,
            category = IconCategory.CODE,
            labelRu = "Баг / Отладка",
            labelEn = "Bug",
            tags = listOf("ошибка", "дебаг", "исправление")
        ),        IconEntry(
            key = "binary",
            drawableRes = R.drawable.ic_cat_binary,
            category = IconCategory.CODE,
            labelRu = "Двоичный код",
            labelEn = "Binary",
            tags = listOf("алгоритмы", "биты")
        ),        IconEntry(
            key = "file-code",
            drawableRes = R.drawable.ic_cat_file_code,
            category = IconCategory.CODE,
            labelRu = "Файл кода",
            labelEn = "Code File",
            tags = listOf("исходник", "модуль")
        ),        IconEntry(
            key = "blocks",
            drawableRes = R.drawable.ic_cat_blocks,
            category = IconCategory.CODE,
            labelRu = "Модули / Блоки",
            labelEn = "Blocks",
            tags = listOf("компоненты", "архитектура")
        ),        IconEntry(
            key = "workflow",
            drawableRes = R.drawable.ic_cat_workflow,
            category = IconCategory.CODE,
            labelRu = "Пайплайн / Workflow",
            labelEn = "Workflow",
            tags = listOf("процессы", "ci/cd")
        ),        IconEntry(
            key = "network",
            drawableRes = R.drawable.ic_cat_network,
            category = IconCategory.CODE,
            labelRu = "Сеть / Граф",
            labelEn = "Network",
            tags = listOf("интернет", "соединение", "топология")
        ),        IconEntry(
            key = "cable",
            drawableRes = R.drawable.ic_cat_cable,
            category = IconCategory.CODE,
            labelRu = "Кабель",
            labelEn = "Cable",
            tags = listOf("подключение", "провода")
        ),        IconEntry(
            key = "qr-code",
            drawableRes = R.drawable.ic_cat_qr_code,
            category = IconCategory.CODE,
            labelRu = "QR-код",
            labelEn = "QR Code",
            tags = listOf("сканирование", "ссылка")
        ),        IconEntry(
            key = "heart",
            drawableRes = R.drawable.ic_cat_heart,
            category = IconCategory.HEALTH,
            labelRu = "Сердце",
            labelEn = "Heart",
            tags = listOf("здоровье", "кардио", "жизнь")
        ),        IconEntry(
            key = "activity",
            drawableRes = R.drawable.ic_cat_activity,
            category = IconCategory.HEALTH,
            labelRu = "Пульс / Активность",
            labelEn = "Pulse",
            tags = listOf("ритм", "экг", "кардио")
        ),        IconEntry(
            key = "pill",
            drawableRes = R.drawable.ic_cat_pill,
            category = IconCategory.HEALTH,
            labelRu = "Таблетка / Витамины",
            labelEn = "Pill",
            tags = listOf("лекарства", "бады", "аптека")
        ),        IconEntry(
            key = "stethoscope",
            drawableRes = R.drawable.ic_cat_stethoscope,
            category = IconCategory.HEALTH,
            labelRu = "Стетоскоп",
            labelEn = "Stethoscope",
            tags = listOf("врач", "чекап", "медицина")
        ),        IconEntry(
            key = "thermometer",
            drawableRes = R.drawable.ic_cat_thermometer,
            category = IconCategory.HEALTH,
            labelRu = "Термометр",
            labelEn = "Thermometer",
            tags = listOf("температура", "здоровье")
        ),        IconEntry(
            key = "shield",
            drawableRes = R.drawable.ic_cat_shield,
            category = IconCategory.HEALTH,
            labelRu = "Иммунитет / Защита",
            labelEn = "Shield",
            tags = listOf("профилактика", "защита")
        ),        IconEntry(
            key = "shield-check",
            drawableRes = R.drawable.ic_cat_shield_check,
            category = IconCategory.HEALTH,
            labelRu = "Проверено / Здоров",
            labelEn = "Shield Check",
            tags = listOf("безопасность", "норма")
        ),        IconEntry(
            key = "smile",
            drawableRes = R.drawable.ic_cat_smile,
            category = IconCategory.HEALTH,
            labelRu = "Улыбка / Настроение",
            labelEn = "Smile",
            tags = listOf("психика", "радость", "позитив")
        ),        IconEntry(
            key = "eye",
            drawableRes = R.drawable.ic_cat_eye,
            category = IconCategory.HEALTH,
            labelRu = "Зрение / Глаза",
            labelEn = "Eye",
            tags = listOf("зарядка для глаз", "осмотр")
        ),        IconEntry(
            key = "cross",
            drawableRes = R.drawable.ic_cat_cross,
            category = IconCategory.HEALTH,
            labelRu = "Медицинский крест",
            labelEn = "Cross",
            tags = listOf("первая помощь", "клиника")
        ),        IconEntry(
            key = "bandage",
            drawableRes = R.drawable.ic_cat_bandage,
            category = IconCategory.HEALTH,
            labelRu = "Пластырь",
            labelEn = "Bandage",
            tags = listOf("заживление", "восстановление")
        ),        IconEntry(
            key = "syringe",
            drawableRes = R.drawable.ic_cat_syringe,
            category = IconCategory.HEALTH,
            labelRu = "Прививка / Анализ",
            labelEn = "Syringe",
            tags = listOf("вакцина", "анализы")
        ),        IconEntry(
            key = "dna",
            drawableRes = R.drawable.ic_cat_dna,
            category = IconCategory.HEALTH,
            labelRu = "ДНК / Генетика",
            labelEn = "DNA",
            tags = listOf("биология", "тело")
        ),        IconEntry(
            key = "sparkle",
            drawableRes = R.drawable.ic_cat_sparkle,
            category = IconCategory.HEALTH,
            labelRu = "Свежесть / Тонус",
            labelEn = "Sparkle",
            tags = listOf("энергия", "чистота")
        ),        IconEntry(
            key = "weight",
            drawableRes = R.drawable.ic_cat_weight,
            category = IconCategory.HEALTH,
            labelRu = "Весы",
            labelEn = "Weight",
            tags = listOf("масса тела", "взвешивание", "форма")
        ),        IconEntry(
            key = "dumbbell",
            drawableRes = R.drawable.ic_cat_dumbbell,
            category = IconCategory.SPORT,
            labelRu = "Гантель / Зал",
            labelEn = "Dumbbell",
            tags = listOf("силовые", "тренировка", "мышцы", "фитнес")
        ),        IconEntry(
            key = "bike",
            drawableRes = R.drawable.ic_cat_bike,
            category = IconCategory.SPORT,
            labelRu = "Велосипед",
            labelEn = "Bike",
            tags = listOf("вело", "кардио", "поездка")
        ),        IconEntry(
            key = "footprints",
            drawableRes = R.drawable.ic_cat_footprints,
            category = IconCategory.SPORT,
            labelRu = "Шаги / Ходьба",
            labelEn = "Footprints",
            tags = listOf("прогулка", "10000 шагов", "дистанция")
        ),        IconEntry(
            key = "flame",
            drawableRes = R.drawable.ic_cat_flame,
            category = IconCategory.SPORT,
            labelRu = "Калории / Огонь",
            labelEn = "Flame",
            tags = listOf("сжигание", "интенсивность", "стрик")
        ),        IconEntry(
            key = "trophy",
            drawableRes = R.drawable.ic_cat_trophy,
            category = IconCategory.SPORT,
            labelRu = "Кубок / Победа",
            labelEn = "Trophy",
            tags = listOf("соревнование", "достижение", "чемпион")
        ),        IconEntry(
            key = "medal",
            drawableRes = R.drawable.ic_cat_medal,
            category = IconCategory.SPORT,
            labelRu = "Медаль",
            labelEn = "Medal",
            tags = listOf("награда", "результат")
        ),        IconEntry(
            key = "timer",
            drawableRes = R.drawable.ic_cat_timer,
            category = IconCategory.SPORT,
            labelRu = "Секундомер",
            labelEn = "Timer",
            tags = listOf("интервалы", "спринт", "время")
        ),        IconEntry(
            key = "swords",
            drawableRes = R.drawable.ic_cat_swords,
            category = IconCategory.SPORT,
            labelRu = "Единоборства",
            labelEn = "Swords",
            tags = listOf("бокс", "борьба", "спарринг")
        ),        IconEntry(
            key = "flag",
            drawableRes = R.drawable.ic_cat_flag,
            category = IconCategory.SPORT,
            labelRu = "Финиш / Флаг",
            labelEn = "Flag",
            tags = listOf("цель", "старт", "марафон")
        ),        IconEntry(
            key = "award",
            drawableRes = R.drawable.ic_cat_award,
            category = IconCategory.SPORT,
            labelRu = "Грамота",
            labelEn = "Award",
            tags = listOf("достижения", "спорт")
        ),        IconEntry(
            key = "zap",
            drawableRes = R.drawable.ic_cat_zap,
            category = IconCategory.SPORT,
            labelRu = "Энергия / Зарядка",
            labelEn = "Zap",
            tags = listOf("разминка", "бодрость")
        ),        IconEntry(
            key = "scale",
            drawableRes = R.drawable.ic_cat_scale,
            category = IconCategory.SPORT,
            labelRu = "Баланс / Равновесие",
            labelEn = "Scale",
            tags = listOf("йога", "растяжка")
        ),        IconEntry(
            key = "mountain",
            drawableRes = R.drawable.ic_cat_mountain,
            category = IconCategory.SPORT,
            labelRu = "Горы / Поход",
            labelEn = "Mountain",
            tags = listOf("хайкинг", "треккинг")
        ),        IconEntry(
            key = "navigation",
            drawableRes = R.drawable.ic_cat_navigation,
            category = IconCategory.SPORT,
            labelRu = "Навигация / Маршрут",
            labelEn = "Navigation",
            tags = listOf("пробежка", "трек")
        ),        IconEntry(
            key = "sun",
            drawableRes = R.drawable.ic_cat_sun,
            category = IconCategory.ROUTINE,
            labelRu = "Утро / Солнце",
            labelEn = "Sun",
            tags = listOf("подъем", "утренний ритуал")
        ),        IconEntry(
            key = "sunrise",
            drawableRes = R.drawable.ic_cat_sunrise,
            category = IconCategory.ROUTINE,
            labelRu = "Рассвет",
            labelEn = "Sunrise",
            tags = listOf("ранний подъем", "магия утра")
        ),        IconEntry(
            key = "sunset",
            drawableRes = R.drawable.ic_cat_sunset,
            category = IconCategory.ROUTINE,
            labelRu = "Закат",
            labelEn = "Sunset",
            tags = listOf("вечер", "итоги дня")
        ),        IconEntry(
            key = "alarm-clock",
            drawableRes = R.drawable.ic_cat_alarm_clock,
            category = IconCategory.ROUTINE,
            labelRu = "Будильник",
            labelEn = "Alarm Clock",
            tags = listOf("пробуждение", "время")
        ),        IconEntry(
            key = "calendar-clock",
            drawableRes = R.drawable.ic_cat_calendar_clock,
            category = IconCategory.ROUTINE,
            labelRu = "Расписание",
            labelEn = "Schedule",
            tags = listOf("режим", "тайминг")
        ),        IconEntry(
            key = "hourglass",
            drawableRes = R.drawable.ic_cat_hourglass,
            category = IconCategory.ROUTINE,
            labelRu = "Песочные часы",
            labelEn = "Hourglass",
            tags = listOf("фокус", "помодоро", "терпение")
        ),        IconEntry(
            key = "check-circle-2",
            drawableRes = R.drawable.ic_cat_check_circle_2,
            category = IconCategory.ROUTINE,
            labelRu = "Выполнено / Галочка",
            labelEn = "Check Circle",
            tags = listOf("привычка", "отметка")
        ),        IconEntry(
            key = "list-todo",
            drawableRes = R.drawable.ic_cat_list_todo,
            category = IconCategory.ROUTINE,
            labelRu = "Список привычек",
            labelEn = "Habit List",
            tags = listOf("рутина", "план на день")
        ),        IconEntry(
            key = "bell",
            drawableRes = R.drawable.ic_cat_bell,
            category = IconCategory.ROUTINE,
            labelRu = "Колокольчик",
            labelEn = "Notification",
            tags = listOf("напоминание", "уведомление")
        ),        IconEntry(
            key = "repeat",
            drawableRes = R.drawable.ic_cat_repeat,
            category = IconCategory.ROUTINE,
            labelRu = "Повторение",
            labelEn = "Repeat",
            tags = listOf("регулярность", "цикл")
        ),        IconEntry(
            key = "refresh-cw",
            drawableRes = R.drawable.ic_cat_refresh_cw,
            category = IconCategory.ROUTINE,
            labelRu = "Обновление / Цикл",
            labelEn = "Refresh",
            tags = listOf("ежедневно", "привыкание")
        ),        IconEntry(
            key = "sparkles",
            drawableRes = R.drawable.ic_cat_sparkles,
            category = IconCategory.ROUTINE,
            labelRu = "Сияние / Магия",
            labelEn = "Sparkles",
            tags = listOf("чистота", "вдохновение")
        ),        IconEntry(
            key = "sun-medium",
            drawableRes = R.drawable.ic_cat_sun_medium,
            category = IconCategory.ROUTINE,
            labelRu = "День",
            labelEn = "Day Sun",
            tags = listOf("дневные дела", "активность")
        ),        IconEntry(
            key = "apple",
            drawableRes = R.drawable.ic_cat_apple,
            category = IconCategory.FOOD,
            labelRu = "Яблоко / Фрукты",
            labelEn = "Apple",
            tags = listOf("здоровое питание", "витамины", "перекус")
        ),        IconEntry(
            key = "utensils",
            drawableRes = R.drawable.ic_cat_utensils,
            category = IconCategory.FOOD,
            labelRu = "Приборы / Еда",
            labelEn = "Utensils",
            tags = listOf("обед", "завтрак", "ужин", "прием пищи")
        ),        IconEntry(
            key = "coffee",
            drawableRes = R.drawable.ic_cat_coffee,
            category = IconCategory.FOOD,
            labelRu = "Кофе",
            labelEn = "Coffee",
            tags = listOf("эспрессо", "капучино", "бодрость")
        ),        IconEntry(
            key = "cup-soda",
            drawableRes = R.drawable.ic_cat_cup_soda,
            category = IconCategory.FOOD,
            labelRu = "Напиток",
            labelEn = "Drink",
            tags = listOf("сок", "лимонад")
        ),        IconEntry(
            key = "cookie",
            drawableRes = R.drawable.ic_cat_cookie,
            category = IconCategory.FOOD,
            labelRu = "Печенье / Сладкое",
            labelEn = "Cookie",
            tags = listOf("десерт", "сахар", "ограничение")
        ),        IconEntry(
            key = "droplet",
            drawableRes = R.drawable.ic_cat_droplet,
            category = IconCategory.FOOD,
            labelRu = "Вода / Капля",
            labelEn = "Water Droplet",
            tags = listOf("гидратация", "пить воду", "2 литра")
        ),        IconEntry(
            key = "salad",
            drawableRes = R.drawable.ic_cat_salad,
            category = IconCategory.FOOD,
            labelRu = "Салат / Овощи",
            labelEn = "Salad",
            tags = listOf("клетчатка", "пп", "зелень")
        ),        IconEntry(
            key = "pizza",
            drawableRes = R.drawable.ic_cat_pizza,
            category = IconCategory.FOOD,
            labelRu = "Пицца / Читмил",
            labelEn = "Pizza",
            tags = listOf("фастфуд", "читмил")
        ),        IconEntry(
            key = "wine",
            drawableRes = R.drawable.ic_cat_wine,
            category = IconCategory.FOOD,
            labelRu = "Бокал вина",
            labelEn = "Wine",
            tags = listOf("алкоголь", "ограничение")
        ),        IconEntry(
            key = "beer",
            drawableRes = R.drawable.ic_cat_beer,
            category = IconCategory.FOOD,
            labelRu = "Пиво",
            labelEn = "Beer",
            tags = listOf("бар", "алкоголь")
        ),        IconEntry(
            key = "cake",
            drawableRes = R.drawable.ic_cat_cake,
            category = IconCategory.FOOD,
            labelRu = "Торт / Праздник",
            labelEn = "Cake",
            tags = listOf("сладкое", "день рождения")
        ),        IconEntry(
            key = "soup",
            drawableRes = R.drawable.ic_cat_soup,
            category = IconCategory.FOOD,
            labelRu = "Суп / Горячее",
            labelEn = "Soup",
            tags = listOf("обед", "первое блюдо")
        ),        IconEntry(
            key = "glass-water",
            drawableRes = R.drawable.ic_cat_glass_water,
            category = IconCategory.FOOD,
            labelRu = "Стакан воды",
            labelEn = "Glass of Water",
            tags = listOf("водный баланс", "утренний стакан")
        ),        IconEntry(
            key = "moon",
            drawableRes = R.drawable.ic_cat_moon,
            category = IconCategory.SLEEP,
            labelRu = "Луна / Ночь",
            labelEn = "Moon",
            tags = listOf("сон", "отбой", "ночной режим")
        ),        IconEntry(
            key = "moon-star",
            drawableRes = R.drawable.ic_cat_moon_star,
            category = IconCategory.SLEEP,
            labelRu = "Ночное небо",
            labelEn = "Moon & Star",
            tags = listOf("глубокий сон", "релакс")
        ),        IconEntry(
            key = "bed",
            drawableRes = R.drawable.ic_cat_bed,
            category = IconCategory.SLEEP,
            labelRu = "Кровать",
            labelEn = "Bed",
            tags = listOf("спать", "ложиться вовремя")
        ),        IconEntry(
            key = "bed-double",
            drawableRes = R.drawable.ic_cat_bed_double,
            category = IconCategory.SLEEP,
            labelRu = "Двуспальная кровать",
            labelEn = "Double Bed",
            tags = listOf("отдых", "спальня")
        ),        IconEntry(
            key = "cloud-moon",
            drawableRes = R.drawable.ic_cat_cloud_moon,
            category = IconCategory.SLEEP,
            labelRu = "Облако и луна",
            labelEn = "Cloudy Moon",
            tags = listOf("вечер", "тишина")
        ),        IconEntry(
            key = "cloud",
            drawableRes = R.drawable.ic_cat_cloud,
            category = IconCategory.SLEEP,
            labelRu = "Облако",
            labelEn = "Cloud",
            tags = listOf("мысли", "спокойствие")
        ),        IconEntry(
            key = "stars",
            drawableRes = R.drawable.ic_cat_stars,
            category = IconCategory.SLEEP,
            labelRu = "Звезды",
            labelEn = "Stars",
            tags = listOf("ночь", "мечты")
        ),        IconEntry(
            key = "home",
            drawableRes = R.drawable.ic_cat_home,
            category = IconCategory.HOME,
            labelRu = "Дом",
            labelEn = "Home",
            tags = listOf("семья", "быт", "уют")
        ),        IconEntry(
            key = "shopping-cart",
            drawableRes = R.drawable.ic_cat_shopping_cart,
            category = IconCategory.HOME,
            labelRu = "Тележка покупок",
            labelEn = "Shopping Cart",
            tags = listOf("магазин", "продукты")
        ),        IconEntry(
            key = "shopping-bag",
            drawableRes = R.drawable.ic_cat_shopping_bag,
            category = IconCategory.HOME,
            labelRu = "Пакет покупок",
            labelEn = "Shopping Bag",
            tags = listOf("шоппинг", "заказ")
        ),        IconEntry(
            key = "trash-2",
            drawableRes = R.drawable.ic_cat_trash_2,
            category = IconCategory.HOME,
            labelRu = "Мусорная корзина",
            labelEn = "Trash",
            tags = listOf("вынести мусор", "уборка", "расхламление")
        ),        IconEntry(
            key = "wrench",
            drawableRes = R.drawable.ic_cat_wrench,
            category = IconCategory.HOME,
            labelRu = "Гаечный ключ",
            labelEn = "Wrench",
            tags = listOf("ремонт", "инструменты", "починка")
        ),        IconEntry(
            key = "hammer",
            drawableRes = R.drawable.ic_cat_hammer,
            category = IconCategory.HOME,
            labelRu = "Молоток",
            labelEn = "Hammer",
            tags = listOf("строительство", "мастерская")
        ),        IconEntry(
            key = "bath",
            drawableRes = R.drawable.ic_cat_bath,
            category = IconCategory.HOME,
            labelRu = "Ванна / Душ",
            labelEn = "Bath",
            tags = listOf("гигиена", "уход за собой", "купание")
        ),        IconEntry(
            key = "sprout",
            drawableRes = R.drawable.ic_cat_sprout,
            category = IconCategory.HOME,
            labelRu = "Росток / Цветы",
            labelEn = "Sprout",
            tags = listOf("растения", "полив", "сад")
        ),        IconEntry(
            key = "cat",
            drawableRes = R.drawable.ic_cat_cat,
            category = IconCategory.HOME,
            labelRu = "Кот",
            labelEn = "Cat",
            tags = listOf("питомцы", "кормление кота")
        ),        IconEntry(
            key = "dog",
            drawableRes = R.drawable.ic_cat_dog,
            category = IconCategory.HOME,
            labelRu = "Собака",
            labelEn = "Dog",
            tags = listOf("выгул собаки", "питомец")
        ),        IconEntry(
            key = "package",
            drawableRes = R.drawable.ic_cat_package,
            category = IconCategory.HOME,
            labelRu = "Посылка / Коробка",
            labelEn = "Package",
            tags = listOf("доставка", "почта")
        ),        IconEntry(
            key = "key",
            drawableRes = R.drawable.ic_cat_key,
            category = IconCategory.HOME,
            labelRu = "Ключ",
            labelEn = "Key",
            tags = listOf("безопасность", "доступ")
        ),        IconEntry(
            key = "door-closed",
            drawableRes = R.drawable.ic_cat_door_closed,
            category = IconCategory.HOME,
            labelRu = "Дверь",
            labelEn = "Door",
            tags = listOf("дом", "выход")
        ),        IconEntry(
            key = "washing-machine",
            drawableRes = R.drawable.ic_cat_washing_machine,
            category = IconCategory.HOME,
            labelRu = "Стиральная машина",
            labelEn = "Washing Machine",
            tags = listOf("стирка", "белье")
        ),        IconEntry(
            key = "armchair",
            drawableRes = R.drawable.ic_cat_armchair,
            category = IconCategory.HOME,
            labelRu = "Кресло / Отдых",
            labelEn = "Armchair",
            tags = listOf("чтение в кресле", "уют")
        ),        IconEntry(
            key = "lamp",
            drawableRes = R.drawable.ic_cat_lamp,
            category = IconCategory.HOME,
            labelRu = "Лампа / Торшер",
            labelEn = "Lamp",
            tags = listOf("свет", "вечерний уют")
        ),        IconEntry(
            key = "user",
            drawableRes = R.drawable.ic_cat_user,
            category = IconCategory.PEOPLE,
            labelRu = "Пользователь / Личное",
            labelEn = "User",
            tags = listOf("я", "профиль", "индивидуальное")
        ),        IconEntry(
            key = "users",
            drawableRes = R.drawable.ic_cat_users,
            category = IconCategory.PEOPLE,
            labelRu = "Люди / Семья / Команда",
            labelEn = "Users",
            tags = listOf("друзья", "общение", "коллеги")
        ),        IconEntry(
            key = "user-check",
            drawableRes = R.drawable.ic_cat_user_check,
            category = IconCategory.PEOPLE,
            labelRu = "Знакомство / Контакт",
            labelEn = "User Check",
            tags = listOf("связь", "человек")
        ),        IconEntry(
            key = "message-circle",
            drawableRes = R.drawable.ic_cat_message_circle,
            category = IconCategory.PEOPLE,
            labelRu = "Диалог / Сообщение",
            labelEn = "Message Circle",
            tags = listOf("звонок другу", "чат")
        ),        IconEntry(
            key = "message-square",
            drawableRes = R.drawable.ic_cat_message_square,
            category = IconCategory.PEOPLE,
            labelRu = "Чат / Разговор",
            labelEn = "Message Square",
            tags = listOf("переписка", "ответы")
        ),        IconEntry(
            key = "heart-handshake",
            drawableRes = R.drawable.ic_cat_heart_handshake,
            category = IconCategory.PEOPLE,
            labelRu = "Забота / Помощь",
            labelEn = "Care & Help",
            tags = listOf("благотворительность", "поддержка")
        ),        IconEntry(
            key = "handshake",
            drawableRes = R.drawable.ic_cat_handshake,
            category = IconCategory.PEOPLE,
            labelRu = "Рукопожатие",
            labelEn = "Handshake",
            tags = listOf("договоренность", "партнерство")
        ),        IconEntry(
            key = "send",
            drawableRes = R.drawable.ic_cat_send,
            category = IconCategory.PEOPLE,
            labelRu = "Отправить",
            labelEn = "Send",
            tags = listOf("сообщение", "письмо")
        ),        IconEntry(
            key = "share-2",
            drawableRes = R.drawable.ic_cat_share_2,
            category = IconCategory.PEOPLE,
            labelRu = "Поделиться",
            labelEn = "Share",
            tags = listOf("репост", "контент")
        ),        IconEntry(
            key = "smile-plus",
            drawableRes = R.drawable.ic_cat_smile_plus,
            category = IconCategory.PEOPLE,
            labelRu = "Радость общения",
            labelEn = "Smile Plus",
            tags = listOf("комплименты", "эмоции")
        ),        IconEntry(
            key = "party-popper",
            drawableRes = R.drawable.ic_cat_party_popper,
            category = IconCategory.PEOPLE,
            labelRu = "Праздник / Вечеринка",
            labelEn = "Party",
            tags = listOf("веселье", "встреча друзей")
        ),        IconEntry(
            key = "gift",
            drawableRes = R.drawable.ic_cat_gift,
            category = IconCategory.PEOPLE,
            labelRu = "Подарок",
            labelEn = "Gift",
            tags = listOf("внимание", "сюрприз", "поздравление")
        ),        IconEntry(
            key = "wallet",
            drawableRes = R.drawable.ic_cat_wallet,
            category = IconCategory.FINANCE,
            labelRu = "Кошелек",
            labelEn = "Wallet",
            tags = listOf("бюджет", "деньги", "накопления")
        ),        IconEntry(
            key = "credit-card",
            drawableRes = R.drawable.ic_cat_credit_card,
            category = IconCategory.FINANCE,
            labelRu = "Банковская карта",
            labelEn = "Credit Card",
            tags = listOf("расходы", "оплата")
        ),        IconEntry(
            key = "coins",
            drawableRes = R.drawable.ic_cat_coins,
            category = IconCategory.FINANCE,
            labelRu = "Монеты / Сбережения",
            labelEn = "Coins",
            tags = listOf("мелочь", "инвестиции")
        ),        IconEntry(
            key = "banknote",
            drawableRes = R.drawable.ic_cat_banknote,
            category = IconCategory.FINANCE,
            labelRu = "Купюра / Доход",
            labelEn = "Banknote",
            tags = listOf("зарплата", "наличные")
        ),        IconEntry(
            key = "piggy-bank",
            drawableRes = R.drawable.ic_cat_piggy_bank,
            category = IconCategory.FINANCE,
            labelRu = "Копилка",
            labelEn = "Piggy Bank",
            tags = listOf("сбережения", "финансовая подушка")
        ),        IconEntry(
            key = "trending-up",
            drawableRes = R.drawable.ic_cat_trending_up,
            category = IconCategory.FINANCE,
            labelRu = "Рост / Инвестиции",
            labelEn = "Trending Up",
            tags = listOf("прибыль", "акции")
        ),        IconEntry(
            key = "receipt",
            drawableRes = R.drawable.ic_cat_receipt,
            category = IconCategory.FINANCE,
            labelRu = "Чек / Учет расходов",
            labelEn = "Receipt",
            tags = listOf("бухгалтерия", "траты")
        ),        IconEntry(
            key = "tag",
            drawableRes = R.drawable.ic_cat_tag,
            category = IconCategory.FINANCE,
            labelRu = "Ценник / Скидки",
            labelEn = "Tag",
            tags = listOf("покупки", "экономия")
        ),        IconEntry(
            key = "dollar-sign",
            drawableRes = R.drawable.ic_cat_dollar_sign,
            category = IconCategory.FINANCE,
            labelRu = "Доллар / Валюта",
            labelEn = "Currency",
            tags = listOf("деньги", "финансы")
        ),        IconEntry(
            key = "percent",
            drawableRes = R.drawable.ic_cat_percent,
            category = IconCategory.FINANCE,
            labelRu = "Проценты / Ставка",
            labelEn = "Percent",
            tags = listOf("кэшбэк", "депозит")
        ),        IconEntry(
            key = "shopping-basket",
            drawableRes = R.drawable.ic_cat_shopping_basket,
            category = IconCategory.FINANCE,
            labelRu = "Корзина",
            labelEn = "Basket",
            tags = listOf("супермаркет", "бюджет")
        ),        IconEntry(
            key = "landmark",
            drawableRes = R.drawable.ic_cat_landmark,
            category = IconCategory.FINANCE,
            labelRu = "Банк",
            labelEn = "Bank",
            tags = listOf("счет", "вклад")
        ),        IconEntry(
            key = "palette",
            drawableRes = R.drawable.ic_cat_palette,
            category = IconCategory.ART,
            labelRu = "Палитра / Живопись",
            labelEn = "Palette",
            tags = listOf("рисование", "скетчинг", "арт")
        ),        IconEntry(
            key = "brush",
            drawableRes = R.drawable.ic_cat_brush,
            category = IconCategory.ART,
            labelRu = "Кисть",
            labelEn = "Brush",
            tags = listOf("краски", "холст")
        ),        IconEntry(
            key = "camera",
            drawableRes = R.drawable.ic_cat_camera,
            category = IconCategory.ART,
            labelRu = "Камера / Фото",
            labelEn = "Camera",
            tags = listOf("фотография", "съемка")
        ),        IconEntry(
            key = "music",
            drawableRes = R.drawable.ic_cat_music,
            category = IconCategory.ART,
            labelRu = "Музыка / Ноты",
            labelEn = "Music",
            tags = listOf("инструменты", "сочинение")
        ),        IconEntry(
            key = "headphones",
            drawableRes = R.drawable.ic_cat_headphones,
            category = IconCategory.ART,
            labelRu = "Наушники / Подкасты",
            labelEn = "Headphones",
            tags = listOf("слушать музыку", "аудиокниги")
        ),        IconEntry(
            key = "mic",
            drawableRes = R.drawable.ic_cat_mic,
            category = IconCategory.ART,
            labelRu = "Микрофон / Вокал",
            labelEn = "Microphone",
            tags = listOf("пение", "подкаст", "голос")
        ),        IconEntry(
            key = "video",
            drawableRes = R.drawable.ic_cat_video,
            category = IconCategory.ART,
            labelRu = "Видеокамера",
            labelEn = "Video",
            tags = listOf("youtube", "монтаж")
        ),        IconEntry(
            key = "film",
            drawableRes = R.drawable.ic_cat_film,
            category = IconCategory.ART,
            labelRu = "Кино / Фильмы",
            labelEn = "Film",
            tags = listOf("просмотр фильмов", "кинематограф")
        ),        IconEntry(
            key = "gamepad-2",
            drawableRes = R.drawable.ic_cat_gamepad_2,
            category = IconCategory.ART,
            labelRu = "Геймпад / Игры",
            labelEn = "Gamepad",
            tags = listOf("игры", "отдых")
        ),        IconEntry(
            key = "scissors",
            drawableRes = R.drawable.ic_cat_scissors,
            category = IconCategory.ART,
            labelRu = "Ножницы / Хендмейд",
            labelEn = "Scissors",
            tags = listOf("рукоделие", "крафт")
        ),        IconEntry(
            key = "wand-2",
            drawableRes = R.drawable.ic_cat_wand_2,
            category = IconCategory.ART,
            labelRu = "Магия / Волшебство",
            labelEn = "Magic Wand",
            tags = listOf("творчество", "креатив")
        ),        IconEntry(
            key = "guitar",
            drawableRes = R.drawable.ic_cat_guitar,
            category = IconCategory.ART,
            labelRu = "Гитара",
            labelEn = "Guitar",
            tags = listOf("игра на гитаре", "аккорды")
        ),        IconEntry(
            key = "piano",
            drawableRes = R.drawable.ic_cat_piano,
            category = IconCategory.ART,
            labelRu = "Фортепиано",
            labelEn = "Piano",
            tags = listOf("клавиши", "музыкальная школа")
        ),        IconEntry(
            key = "globe",
            drawableRes = R.drawable.ic_cat_globe,
            category = IconCategory.TRAVEL,
            labelRu = "Глобус / Мир",
            labelEn = "Globe",
            tags = listOf("путешествия", "география")
        ),        IconEntry(
            key = "plane",
            drawableRes = R.drawable.ic_cat_plane,
            category = IconCategory.TRAVEL,
            labelRu = "Самолет / Перелет",
            labelEn = "Plane",
            tags = listOf("рейс", "отпуск")
        ),        IconEntry(
            key = "map",
            drawableRes = R.drawable.ic_cat_map,
            category = IconCategory.TRAVEL,
            labelRu = "Карта",
            labelEn = "Map",
            tags = listOf("маршрут", "навигация")
        ),        IconEntry(
            key = "map-pin",
            drawableRes = R.drawable.ic_cat_map_pin,
            category = IconCategory.TRAVEL,
            labelRu = "Метка на карте",
            labelEn = "Map Pin",
            tags = listOf("место", "локация")
        ),        IconEntry(
            key = "mountain-snow",
            drawableRes = R.drawable.ic_cat_mountain_snow,
            category = IconCategory.TRAVEL,
            labelRu = "Снежные горы",
            labelEn = "Snow Mountains",
            tags = listOf("альпинизм", "лыжи")
        ),        IconEntry(
            key = "tree-pine",
            drawableRes = R.drawable.ic_cat_tree_pine,
            category = IconCategory.TRAVEL,
            labelRu = "Ель / Лес",
            labelEn = "Pine Tree",
            tags = listOf("природа", "прогулка в лесу")
        ),        IconEntry(
            key = "trees",
            drawableRes = R.drawable.ic_cat_trees,
            category = IconCategory.TRAVEL,
            labelRu = "Деревья / Парк",
            labelEn = "Trees",
            tags = listOf("экология", "свежий воздух")
        ),        IconEntry(
            key = "umbrella",
            drawableRes = R.drawable.ic_cat_umbrella,
            category = IconCategory.TRAVEL,
            labelRu = "Зонт / Погода",
            labelEn = "Umbrella",
            tags = listOf("дождь", "прогноз")
        ),        IconEntry(
            key = "car",
            drawableRes = R.drawable.ic_cat_car,
            category = IconCategory.TRAVEL,
            labelRu = "Автомобиль",
            labelEn = "Car",
            tags = listOf("вождение", "поездка")
        ),        IconEntry(
            key = "train",
            drawableRes = R.drawable.ic_cat_train,
            category = IconCategory.TRAVEL,
            labelRu = "Поезд",
            labelEn = "Train",
            tags = listOf("жд", "путешествие поездом")
        ),        IconEntry(
            key = "ship",
            drawableRes = R.drawable.ic_cat_ship,
            category = IconCategory.TRAVEL,
            labelRu = "Корабль / Круиз",
            labelEn = "Ship",
            tags = listOf("море", "плавание")
        ),        IconEntry(
            key = "anchor",
            drawableRes = R.drawable.ic_cat_anchor,
            category = IconCategory.TRAVEL,
            labelRu = "Якорь",
            labelEn = "Anchor",
            tags = listOf("причал", "надежность")
        ),        IconEntry(
            key = "tent",
            drawableRes = R.drawable.ic_cat_tent,
            category = IconCategory.TRAVEL,
            labelRu = "Палатка / Кемпинг",
            labelEn = "Tent",
            tags = listOf("поход", "ночевка на природе")
        ),        IconEntry(
            key = "luggage",
            drawableRes = R.drawable.ic_cat_luggage,
            category = IconCategory.TRAVEL,
            labelRu = "Чемодан / Багаж",
            labelEn = "Luggage",
            tags = listOf("сбор вещей", "поездка")
        ),        IconEntry(
            key = "lightbulb",
            drawableRes = R.drawable.ic_cat_lightbulb,
            category = IconCategory.MIND,
            labelRu = "Лампочка / Идея",
            labelEn = "Lightbulb",
            tags = listOf("инсайт", "мысль", "творчество")
        ),        IconEntry(
            key = "puzzle",
            drawableRes = R.drawable.ic_cat_puzzle,
            category = IconCategory.MIND,
            labelRu = "Пазл / Логика",
            labelEn = "Puzzle",
            tags = listOf("загадки", "мышление")
        ),        IconEntry(
            key = "glasses",
            drawableRes = R.drawable.ic_cat_glasses,
            category = IconCategory.MIND,
            labelRu = "Очки / Чтение",
            labelEn = "Glasses",
            tags = listOf("внимательность", "интеллект")
        ),        IconEntry(
            key = "gem",
            drawableRes = R.drawable.ic_cat_gem,
            category = IconCategory.MIND,
            labelRu = "Алмаз / Ценность",
            labelEn = "Gem",
            tags = listOf("саморазвитие", "качество")
        ),        IconEntry(
            key = "flower-2",
            drawableRes = R.drawable.ic_cat_flower_2,
            category = IconCategory.MIND,
            labelRu = "Цветок / Медитация",
            labelEn = "Flower",
            tags = listOf("спокойствие", "гармония")
        ),        IconEntry(
            key = "feather",
            drawableRes = R.drawable.ic_cat_feather,
            category = IconCategory.MIND,
            labelRu = "Перо / Писательство",
            labelEn = "Feather",
            tags = listOf("эссе", "блог", "статьи")
        ),        IconEntry(
            key = "milestone",
            drawableRes = R.drawable.ic_cat_milestone,
            category = IconCategory.MIND,
            labelRu = "Веха / Этап",
            labelEn = "Milestone",
            tags = listOf("цели", "прогресс")
        ),        IconEntry(
            key = "focus",
            drawableRes = R.drawable.ic_cat_focus,
            category = IconCategory.MIND,
            labelRu = "Фокус / Концентрация",
            labelEn = "Focus",
            tags = listOf("глубокая работа", "внимание")
        ),        IconEntry(
            key = "scan-face",
            drawableRes = R.drawable.ic_cat_scan_face,
            category = IconCategory.MIND,
            labelRu = "Лицо / Осознанность",
            labelEn = "Mindfulness",
            tags = listOf("эмоции", "рефлексия")
        ),        IconEntry(
            key = "badge-check",
            drawableRes = R.drawable.ic_cat_badge_check,
            category = IconCategory.MIND,
            labelRu = "Знак качества",
            labelEn = "Verified",
            tags = listOf("навык освоен", "успех")
        ),        IconEntry(
            key = "crown",
            drawableRes = R.drawable.ic_cat_crown,
            category = IconCategory.MIND,
            labelRu = "Корона / Лидерство",
            labelEn = "Crown",
            tags = listOf("уверенность", "достижения")
        ),        IconEntry(
            key = "star",
            drawableRes = R.drawable.ic_cat_star,
            category = IconCategory.MIND,
            labelRu = "Звезда / Избранное",
            labelEn = "Star",
            tags = listOf("лучшее", "топ")
        ),        IconEntry(
            key = "rocket",
            drawableRes = R.drawable.ic_cat_rocket,
            category = IconCategory.MIND,
            labelRu = "Ракета / Старт",
            labelEn = "Rocket",
            tags = listOf("запуск", "прорыв", "быстрый старт")
        ),        IconEntry(
            key = "shield-alert",
            drawableRes = R.drawable.ic_cat_shield_alert,
            category = IconCategory.MIND,
            labelRu = "Самоконтроль",
            labelEn = "Self-Control",
            tags = listOf("дисциплина", "стоп-привычка")
        )
    )

    private val KEY_MAP: Map<String, IconEntry> = ICONS.associateBy { it.key }
    private val DRAWABLE_MAP: Map<Int, IconEntry> = ICONS.associateBy { it.drawableRes }

    fun getIcon(key: String?): IconEntry? {
        if (key.isNullOrBlank()) return null
        val cleanKey = key.removePrefix("vector:")
        return KEY_MAP[cleanKey] ?: KEY_MAP[cleanKey.replace('_', '-')]
    }

    @DrawableRes
    fun getDrawableRes(key: String?, defaultRes: Int = DEFAULT_CONTAINER_ICON_RES): Int {
        return getIcon(key)?.drawableRes ?: defaultRes
    }

    fun search(query: String, category: IconCategory = IconCategory.ALL): List<IconEntry> {
        val trimmed = query.trim().lowercase()
        return ICONS.filter { entry ->
            val matchesCategory = category == IconCategory.ALL || entry.category == category
            if (!matchesCategory) return@filter false
            if (trimmed.isEmpty()) return@filter true
            entry.labelRu.lowercase().contains(trimmed) ||
                entry.labelEn.lowercase().contains(trimmed) ||
                entry.key.lowercase().contains(trimmed) ||
                entry.tags.any { it.lowercase().contains(trimmed) }
        }
    }
}
