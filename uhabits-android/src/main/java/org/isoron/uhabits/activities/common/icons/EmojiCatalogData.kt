/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.icons

data class EmojiEntry(
    val emoji: String,
    val nameRu: String,
    val nameEn: String,
    val category: String,
    val tags: List<String>
)

object EmojiCatalogData {

    val ALL_EMOJIS: List<EmojiEntry> = listOf(
        // Смайлы и эмоции (Smileys & Emotion)
        EmojiEntry("😀", "Улыбка", "Grinning Face", "Smileys", listOf("радость", "счастье", "позитив", "smile", "happy")),
        EmojiEntry("😊", "Счастливое лицо", "Smiling Face", "Smileys", listOf("доброта", "улыбка", "мило", "happy", "kind")),
        EmojiEntry("😎", "Крутой", "Cool Face", "Smileys", listOf("очки", "круто", "успех", "cool", "sunglasses")),
        EmojiEntry("🥳", "Праздник", "Partying Face", "Smileys", listOf("вечеринка", "победа", "празднование", "party", "celebration")),
        EmojiEntry("😇", "Ангел", "Innocent", "Smileys", listOf("невинный", "добрый", "святой", "angel", "good")),
        EmojiEntry("🤩", "В восторге", "Star-Struck", "Smileys", listOf("звезды", "восторг", "вау", "excited", "wow")),
        EmojiEntry("🤔", "Размышление", "Thinking Face", "Smileys", listOf("мысли", "думать", "вопрос", "think", "idea")),
        EmojiEntry("😌", "Спокойствие", "Relieved Face", "Smileys", listOf("релакс", "облегчение", "мир", "calm", "zen")),
        EmojiEntry("😴", "Сон", "Sleeping Face", "Smileys", listOf("спать", "усталость", "отдых", "sleep", "tired")),
        EmojiEntry("🔥", "Огонь", "Fire", "Smileys", listOf("пламя", "жара", "мотивация", "энергия", "streak", "hot")),
        EmojiEntry("⭐", "Звезда", "Star", "Smileys", listOf("успех", "избранное", "награда", "favorite", "gold")),
        EmojiEntry("✨", "Искры", "Sparkles", "Smileys", listOf("магия", "чистота", "новый", "magic", "clean", "shine")),
        EmojiEntry("❤️", "Красное сердце", "Red Heart", "Smileys", listOf("любовь", "здоровье", "жизнь", "love", "heart")),
        EmojiEntry("💖", "Сверкающее сердце", "Sparkling Heart", "Smileys", listOf("забота", "любовь", "радость", "care", "sparkle")),
        EmojiEntry("💯", "Сто очков", "Hundred Points", "Smileys", listOf("идеально", "максимум", "100%", "perfect", "score")),
        EmojiEntry("🎯", "Цель", "Direct Hit", "Smileys", listOf("мишень", "фокус", "точность", "target", "focus", "goal")),

        // Люди и активность (People & Activities)
        EmojiEntry("🏃", "Бег", "Person Running", "Activity", listOf("спорт", "тренировка", "пробежка", "run", "fitness", "cardio")),
        EmojiEntry("🏃‍♂️", "Бегун", "Man Running", "Activity", listOf("спорт", "бег", "тренировка", "runner", "athletics")),
        EmojiEntry("🏃‍♀️", "Бегунья", "Woman Running", "Activity", listOf("спорт", "бег", "тренировка", "runner", "athletics")),
        EmojiEntry("🚶", "Ходьба", "Person Walking", "Activity", listOf("шаги", "прогулка", "свежий воздух", "walk", "steps")),
        EmojiEntry("🏋️", "Тяжелая атлетика", "Weightlifting", "Activity", listOf("зал", "гантели", "штанга", "сила", "gym", "workout")),
        EmojiEntry("🏋️‍♂️", "Атлет", "Man Weightlifting", "Activity", listOf("зал", "бодибилдинг", "качалка", "gym", "strength")),
        EmojiEntry("🏋️‍♀️", "Атлетка", "Woman Weightlifting", "Activity", listOf("зал", "бодибилдинг", "фитнес", "gym", "fitness")),
        EmojiEntry("🧘", "Медитация / Йога", "Person in Lotus", "Activity", listOf("осознанность", "дзен", "растяжка", "meditation", "yoga", "mindfulness")),
        EmojiEntry("🚴", "Велосипед", "Biking", "Activity", listOf("спорт", "поездка", "крутить педали", "bike", "cycling")),
        EmojiEntry("🏊", "Плавание", "Swimming", "Activity", listOf("бассейн", "вода", "спорт", "swim", "pool")),
        EmojiEntry("🧗", "Скалолазание", "Climbing", "Activity", listOf("горы", "экстрим", "спорт", "climb", "bouldering")),
        EmojiEntry("💪", "Мускул / Сила", "Flexed Biceps", "Activity", listOf("мышцы", "спорт", "мотивация", "power", "strength", "biceps")),
        EmojiEntry("🤝", "Рукопожатие", "Handshake", "Activity", listOf("партнерство", "договор", "дружба", "deal", "friendship")),
        EmojiEntry("👍", "Большой палец вверх", "Thumbs Up", "Activity", listOf("хорошо", "согласен", "лайк", "ok", "good", "like")),
        EmojiEntry("👏", "Аплодисменты", "Clapping Hands", "Activity", listOf("браво", "молодец", "похвала", "applause", "cheer")),
        EmojiEntry("🙏", "Молитва / Благодарность", "Folded Hands", "Activity", listOf("спасибо", "намасте", "просьба", "thanks", "prayer")),

        // Учёба, работа и наука (Study & Work)
        EmojiEntry("📚", "Книги", "Books", "Work", listOf("чтение", "учеба", "библиотека", "read", "education", "books")),
        EmojiEntry("📖", "Открытая книга", "Open Book", "Work", listOf("учеба", "литература", "знания", "read", "study")),
        EmojiEntry("🎓", "Шапочка выпускника", "Graduation Cap", "Work", listOf("университет", "диплом", "образование", "degree", "graduation")),
        EmojiEntry("💻", "Ноутбук", "Laptop", "Work", listOf("компьютер", "программирование", "работа", "pc", "coding", "tech")),
        EmojiEntry("🖥️", "Компьютер", "Desktop Computer", "Work", listOf("монитор", "офис", "разработка", "pc", "work")),
        EmojiEntry("📱", "Телефон", "Mobile Phone", "Work", listOf("смартфон", "связь", "сообщения", "phone", "mobile")),
        EmojiEntry("📝", "Заметка", "Memo", "Work", listOf("блокнот", "запись", "план", "notes", "write", "todo")),
        EmojiEntry("✏️", "Карандаш", "Pencil", "Work", listOf("писать", "рисовать", "черновик", "draw", "draft")),
        EmojiEntry("💼", "Портфель", "Briefcase", "Work", listOf("работа", "бизнес", "офис", "career", "job")),
        EmojiEntry("📊", "Диаграмма", "Bar Chart", "Work", listOf("статистика", "график", "отчет", "analytics", "growth")),
        EmojiEntry("📈", "Рост", "Chart Increasing", "Work", listOf("прогресс", "успех", "тренд", "increase", "success")),
        EmojiEntry("🧠", "Мозг", "Brain", "Work", listOf("интеллект", "мышление", "память", "mind", "intellect")),
        EmojiEntry("💡", "Лампочка", "Light Bulb", "Work", listOf("идея", "инсайт", "мысль", "idea", "creative")),
        EmojiEntry("🔬", "Микроскоп", "Microscope", "Work", listOf("наука", "исследования", "биология", "science", "lab")),
        EmojiEntry("🚀", "Ракета", "Rocket", "Work", listOf("стартап", "запуск", "скорость", "boost", "launch", "fast")),

        // Здоровье, дом и отдых (Health & Home)
        EmojiEntry("🥗", "Салат", "Green Salad", "Health", listOf("здоровая еда", "диета", "овощи", "diet", "healthy", "vegan")),
        EmojiEntry("🍎", "Красное яблоко", "Red Apple", "Health", listOf("фрукты", "витамины", "еда", "fruit", "nutrition")),
        EmojiEntry("🥑", "Авокадо", "Avocado", "Health", listOf("пп", "диета", "полезно", "keto", "healthy")),
        EmojiEntry("💧", "Капля воды", "Droplet", "Health", listOf("пить воду", "гидратация", "аква", "water", "drink", "h2o")),
        EmojiEntry("☕", "Кофе / Чай", "Hot Beverage", "Health", listOf("утро", "бодрость", "кофеин", "coffee", "tea", "morning")),
        EmojiEntry("🍵", "Зеленый чай", "Teacup", "Health", listOf("матча", "чай", "травы", "tea", "matcha")),
        EmojiEntry("💊", "Таблетка", "Pill", "Health", listOf("витамины", "лекарство", "аптека", "medicine", "vitamins")),
        EmojiEntry("🩺", "Стетоскоп", "Stethoscope", "Health", listOf("врач", "медицина", "осмотр", "doctor", "checkup")),
        EmojiEntry("🏠", "Дом", "House", "Home", listOf("уют", "быт", "семья", "home", "building")),
        EmojiEntry("🧹", "Метла / Уборка", "Broom", "Home", listOf("чистота", "порядок", "клининг", "clean", "chores")),
        EmojiEntry("🛌", "Кровать", "Person in Bed", "Home", listOf("сон", "отдых", "спальня", "sleep", "rest")),
        EmojiEntry("⏰", "Будильник", "Alarm Clock", "Home", listOf("время", "подъем", "утро", "clock", "wake up")),
        EmojiEntry("🪴", "Комнатное растение", "Potted Plant", "Home", listOf("цветы", "зелень", "уход", "plant", "green")),
        EmojiEntry("🐕", "Собака", "Dog", "Home", listOf("пес", "питомец", "прогулка", "pet", "puppy")),
        EmojiEntry("🐈", "Кот", "Cat", "Home", listOf("кошка", "питомец", "пушистый", "pet", "kitten")),

        // Финансы (Finance)
        EmojiEntry("💰", "Мешок денег", "Money Bag", "Finance", listOf("богатство", "накопления", "бюджет", "wealth", "cash")),
        EmojiEntry("💳", "Кредитная карта", "Credit Card", "Finance", listOf("оплата", "банк", "покупки", "bank", "payment")),
        EmojiEntry("💵", "Купюра", "Dollar Banknote", "Finance", listOf("деньги", "валюта", "нал", "money", "dollar")),
        EmojiEntry("🪙", "Монета", "Coin", "Finance", listOf("копейка", "сдача", "золото", "gold", "crypto")),
        EmojiEntry("🏦", "Банк", "Bank", "Finance", listOf("депозит", "вклад", "кредит", "finance", "building")),

        // Творчество и досуг (Art & Leisure)
        EmojiEntry("🎨", "Палитра", "Artist Palette", "Art", listOf("рисование", "живопись", "дизайн", "paint", "art", "draw")),
        EmojiEntry("🎵", "Нота", "Musical Note", "Art", listOf("музыка", "трек", "песня", "song", "audio")),
        EmojiEntry("🎸", "Гитара", "Guitar", "Art", listOf("рок", "инструмент", "аккорды", "music", "play")),
        EmojiEntry("🎹", "Клавиши", "Musical Keyboard", "Art", listOf("пианино", "синтезатор", "музыка", "piano")),
        EmojiEntry("📷", "Фотоаппарат", "Camera", "Art", listOf("фотография", "снимок", "память", "photo", "shot")),
        EmojiEntry("🎮", "Геймпад", "Video Game", "Art", listOf("игры", "плейстейшн", "гейминг", "gaming", "play")),
        EmojiEntry("🎬", "Кинохлопушка", "Clapper Board", "Art", listOf("фильмы", "сериалы", "кино", "cinema", "movie")),
        EmojiEntry("🎁", "Подарок", "Wrapped Gift", "Art", listOf("сюрприз", "праздник", "радость", "present", "gift")),

        // Путешествия (Travel)
        EmojiEntry("✈️", "Самолет", "Airplane", "Travel", listOf("полет", "отпуск", "путешествие", "flight", "trip")),
        EmojiEntry("🚗", "Автомобиль", "Automobile", "Travel", listOf("машина", "дорога", "авто", "car", "drive")),
        EmojiEntry("🚆", "Поезд", "Train", "Travel", listOf("рельсы", "метро", "жд", "railway", "trip")),
        EmojiEntry("🌍", "Земной шар", "Globe Europe-Africa", "Travel", listOf("планета", "мир", "страны", "earth", "world")),
        EmojiEntry("🗺️", "Карта мира", "World Map", "Travel", listOf("маршрут", "навигация", "туризм", "map", "travel")),
        EmojiEntry("🏖️", "Пляж", "Beach with Umbrella", "Travel", listOf("море", "песок", "отдых", "sea", "vacation")),
        EmojiEntry("🏕️", "Кемпинг", "Camping", "Travel", listOf("палатка", "поход", "лес", "camp", "nature")),
        EmojiEntry("⛰️", "Гора", "Mountain", "Travel", listOf("вершина", "треккинг", "хайкинг", "peak", "hike"))
    )

    fun search(query: String): List<String> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return ALL_EMOJIS.map { it.emoji }

        return ALL_EMOJIS.filter { entry ->
            entry.emoji == trimmed ||
                entry.nameRu.lowercase().contains(trimmed) ||
                entry.nameEn.lowercase().contains(trimmed) ||
                entry.category.lowercase().contains(trimmed) ||
                entry.tags.any { it.lowercase().contains(trimmed) }
        }.map { it.emoji }
    }
}
