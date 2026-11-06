# PR 2 — единый срок задачи: контракт и совместимость

Статус на 22.10.2026: **основной контракт утверждён; PR 3 разрешён пользователем**, без коммита PR 3. PR 2.1 и PR 2.2 завершены; PR 2.2 зафиксирован локально как `d5cc5c1f648b3ffff111baa86664a8f118d28f3b`. Production Due cutover блокируется отсутствием доказанного серверного capability/write-gate для старых клиентов. Актуальный повторный аудит и реализованная изолированная часть: `TASK_DUE_PR3_COMPATIBILITY.md`.

Ниже сохранён исторический аудит PR 2 на его исходной базе. Утверждения об ошибке текущего sync и запрете начинать PR 3 описывают тот этап: PR 2.1 исправил ISO/legacy numeric transport и raw preservation, PR 2.2 добавил durable inbox/atomic cursor/context guards. Это не решило старые writers, неизвестные поля/события и backup совместимость Due.

Утверждены NONE/DAY/TIMED, неизменность DAY при смене timezone, точный instant и известная исходная зона TIMED, однократное уточнение plan-only без автоматической просрочки, приоритет legacy deadline с сохранением plan, запрет legacy fallback после явного NONE и защита новых полей от старых клиентов. Детали физического хранения и безопасного cutover остаются отдельным этапом. PR 2.1 разрешён только как исправление текущего plannedDate sync.
Дата: 19.10.2026. Корень определён через `git rev-parse --show-toplevel`.
База PR 2: `c6df44ec1ed371a679f348c0d233acf5b4ad9a45`.
PR 1 зафиксирован этим отдельным коммитом по прямому разрешению пользователя и не входит в diff PR 2.

Разрешённая область PR 2: тесты, синтетические фикстуры, документация. Нет изменений main-кода, UI, production DDL, данных, FocusSession, общих разделов, backup/sync реализации. Коммит PR 2 не разрешён и не создавался. Проверки offline, SQLite только in-memory. Производственная миграция единого срока не запускалась и не написана.

## 1. Что делает текущий код

Пути далее относительно корня Git; строки относятся к базе PR 2.

| Участок | Текущий контракт / точка изменения в будущем |
|---|---|
| `core/userTasks/UserTaskModels.kt:42–69` | `plannedDate: LocalDate?`, `deadline: Long?` независимы. Hard-overdue только при `deadline < now`, если задача не завершена. Пропущенный план — другая семантика. Полный префикс: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/`. |
| `core/userTasks/UserTaskServiceImpl.kt:106–116` | `null` в patch сохраняет поле, отдельный clear-флаг очищает. Завершение меняет status/completedAt, не даты. |
| `core/userTasks/sqlite/SQLiteUserTaskRepository.kt:208–224` | ISO `YYYY-MM-DD` в `planned_date TEXT`, epoch milliseconds в `deadline INTEGER`. Числовая строка плана читается как null. |
| `core/userTasks/UserTaskModels.kt:86–99` | Старый ISO-парсер допускает нормализацию невозможных дат; `2026-02-30` становится 02.03.2026. Новый importer не должен повторять это молча. |
| `core/userTasks/sqlite/UserTaskSchema.kt:28–47`, `uhabits-core/assets/main/migrations/30.sql:100–119` | Два поля продублированы в текущем установочном DDL. Нет timezone, признака выбора времени и версии нового срока. |
| `platform/time/Dates.kt:152–153` | `LocalDate.unixTime` — арифметическое UTC epoch-day значение, а не local-midnight пользовательского часового пояса. |
| Android `activities/tasks/TaskDetailBottomSheet.kt:598–607`, `TasksFragment.kt:471,508` | Для дедлайна без выбора времени подставляется 18:00. Узнать по timestamp, выбирал ли пользователь это время, невозможно. Полный Android-префикс: `uhabits-android/src/main/java/org/isoron/uhabits/`. |
| Android `activities/tasks/TasksFragment.kt:411–419`, `TaskDatePicker.kt` | Открытие quick add из «Сегодня» подставляет сегодняшний plan. Material date picker использует UTC для передачи calendar date; выбор времени и deadline используют локальный Calendar. UI также требует будущего перехода, но сейчас не меняется. |
| Android `TaskSmartView.kt:33–43`, `TasksFragment.kt:613–642` | «Сегодня» включает план сегодня, пропущенный план и просрочку; сортировки ориентированы на эти два старых поля. Текущие тесты это фиксируют, не утверждают соответствие новому ТЗ. |
| Android `TaskCalendarEvents.kt:47–60` | До двух событий PLAN/DEADLINE на задачу, в том числе в один день. День deadline вычисляется в текущем timezone устройства. |
| Android `TaskDetailBottomSheet.kt:147–169` | Фокус ограничен существованием задачи/режимом архитектуры/активной сессией, не сроком. Это сохраняется. |
| `core/containers/backup/DatabaseSnapshotter.kt:43–53,873–909` | Snapshot создаёт текущую hard-coded схему и перечисляет 12 колонок задачи. Сырые старые даты копируются без переинтерпретации. Неизвестные будущие колонки не сохраняются, хотя source user_version переносится. |
| `core/containers/backup/ContainerRestoreService.kt:108–135,196–201` | Сначала staging copy, затем проверка manifest; публикация только после проверок. Новую совместимость нужно проверять до потенциально потерьной копии. |
| `core/containers/backup/ContainerBackupManifest.kt:76–77` | Текущий format=4, foundation=1. Простое добавление поля в UserTask не делает его переносимым в архивах. |
| `core/sync/SyncManager.kt:266–285` | Обычная очередь отправляет `plannedDate.unixTime` строкой. |
| Android `sync/SyncCoordinator.kt:1935–1983` | Приёмник сохраняет `planned_date` строкой без преобразования; `INSERT OR REPLACE` заменяет строку задачи. |
| Android `sync/SyncCoordinator.kt:1700–1703` | optionalString обрезает пробелы и сводит empty/`null` к отсутствию. Raw-инвентаризацию перехода нужно выполнять до такого декодирования. |
| Android `sync/SyncCoordinator.kt:2170–2200` | Bootstrap отправляет исходный `planned_date` из SQLite, обычно ISO. Два отправителя фактически используют разные форматы. |

## 2. Воспроизведённая ошибка sync

Реальная цепочка теста: `UserTaskServiceImpl → SyncManager → SyncQueueRecord → SyncCoordinator.applyRemoteEvents → SQLiteUserTaskRepository`. Transport — непосредственная передача неизменённого production payload между двумя in-memory устройствами. Сеть и Supabase не вызываются. Использован существующий SyncCoordinatorNextTest harness; единственное изменение его существующего кода — `private → internal` у фабрики тестового окружения.

Для даты **19.10.2026**:

1. Исходная SQLite-строка: `2026-10-19`.
2. Incremental payload: `"planned_date":"1792368000000"`.
3. Приёмник сохраняет эту же числовую строку.
4. ISO-парсер модели возвращает `null`.
5. При последующем изменении только названия UserTaskService читает null и записывает его обратно: сырая строка удаляется. Обратное событие очищает дату и на первом устройстве.

Дедлайн сохраняется точно, включая миллисекунды. Undated/deadline-only контроли работают. Bootstrap с ISO-строкой сохраняет план и дедлайн; это объясняет, почему тесты синхронизации без дат и проверки bootstrap могли не обнаружить дефект.

Strict gate реально запущен и падает с:

```
plannedDate must survive the real outbound/inbound sync path
expected:<LocalDate(2026-10-19)> but was:<null>
```

Файл: `uhabits-android/src/test/java/org/isoron/uhabits/sync/TaskDateSyncContractTest.kt`.

Воспроизведение из Git root (JDK17):

```powershell
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:UHABIT_PR2_ASSERT_FIXED_SYNC = '1'
.\gradlew.bat :uhabits-android:testDebugUnitTest --tests '*TaskDateSyncContractTest.regressionGate_incrementalRoundTripMustPreservePlannedDate' --console=plain --quiet
Remove-Item Env:UHABIT_PR2_ASSERT_FIXED_SYNC
```

Ожидается ненулевой exit code и именно приведённое assertion failure. Без переменной этот correctness gate явно skipped с объяснением известного дефекта; остальные тесты характеризуют текущее поведение. Это **не зелёный контракт сохранения дат**. Исправление production sync в PR 2 не внесено. Нужен отдельный явно названный fix перед переходом на новый срок: единый формат отправителей, совместимое чтение уже отправленных числовых значений, запрет молчаливой потери raw и регрессионный gate.

Числовые строки нельзя восстанавливать через локальную timezone устройства или эвристику seconds/ms. Для известных payload этого конкретного serializer документирована UTC epoch-day/milliseconds семантика; автоматический repair возможен только при подтверждённом происхождении, строгой проверке и сохранении исходника. В PR 2 repair отсутствует.

## 3. Предложенный контракт Due v1

Один пользовательский срок — tagged union, не набор двух nullable дат:

| Вид | Содержимое | Семантика |
|---|---|---|
| `NONE` | Нет даты и времени | Пользователь явно очистил срок либо он никогда не назначался. |
| `DAY` | Строгая ISO calendar date | Без времени, без timezone, без скрытого timestamp. При смене timezone остаётся тем же календарным днём. |
| `TIMED` | Точный instant + originZoneId | Конкретный момент. В новом вводе timezone известен; в legacy import originZoneId=null означает неизвестность исходной зоны, не UTC. Исходные milliseconds не округляются. |

В тестовом envelope это `version=1`, `kind=NONE/DAY/TIMED`, соответственно `date` либо `instant` и необязательный `originZone`. Это **field-map proposal**, не реализованный JSON/protocol/DDL. Физическое хранение и номер следующей SQLite-версии выбираются в PR 3 после проверки текущего HEAD.

- 00:00, 18:00, 23:59 и любые другие значения являются обычным **явно заданным временем** у нового TIMED; никогда не кодируют DAY.
- DAY и NONE не содержат instant. Несовместимые поля, неизвестная версия/kind и невозможная ISO-дата отклоняются с сохранением исходного входа для диагностики, без нормализации в другую дату.
- Локальный ввод TIMED требует существующую IANA zone. DST gap не сдвигается автоматически; требуется новый выбор. DST overlap требует явного допустимого offset. Сохраняется выбранный instant, timezone нужна для происхождения и редактирования.
- Для существующего deadline время остаётся TIMED, даже если оно 18:00 или 00:00. Само число ничего не доказывает о намерении пользователя.
- При просмотре TIMED проецируется в **текущую зону просмотра**; при путешествии его календарный день может измениться, instant — нет. Это соответствует текущему способу отображения deadline. Такое поведение нужно согласовать до PR 3.
- Нет автоматического назначения срока при открытии редактора. Контекстное назначение из «Сегодня» возможно только явно показанным значением, в будущем UI PR.

Patch отдельно различает **отсутствующее поле = без изменения** и **присутствующий NONE = очистить**. DAY/TIMED заменяют только canonical due. Старые raw поля не используются как fallback после явной очистки — иначе дата воскреснет.

## 4. «Сегодня», просрочка, календарь и фокус

Для активной задачи при одном `now` и одном `viewZone`:

- DAY просрочен только если `due.date < today(viewZone)`. Нет искусственного момента конца дня.
- TIMED просрочен только если `instant < now`; равенство ещё не просрочка, как сейчас.
- Раздел просроченных имеет приоритет. Основной блок «Сегодня»: день срока ровно сегодня **и задача не просрочена**. TIMED сегодня с уже прошедшим временем попадает только в отдельный блок просроченных. Completed не попадает ни в один активный блок.
- Задачи с вчерашним планом, без canonical due, с будущим сроком не попадают в основной блок «Сегодня». Нет My day/ручного включения/нового TaskStatus.
- DAY в календаре — строка дня вне часовой сетки; TIMED — одно событие момента. Старая просрочка остаётся на исходной дате, не переносится автоматически.
- Запуск фокуса доступен независимо от NONE/DAY/TIMED и просрочки. Он не меняет due/status. Новые ограничения FocusSession не добавляются.

Это будущие правила; текущие TaskSmartView/UI/Calendar не изменены.

## 5. Таблица перехода legacy

Обязательный неизменяемый снимок для каждой задачи: stable task UUID, **точный raw planned_date**, nullable deadline milliseconds, исходная revision/updated_at и формат/версия источника. NULL, пустая строка и строка `null` не склеиваются. Для audit fingerprint используется эта сырая запись, а не объект UserTask с уже потерянной датой.

| Старые поля | Canonical предложение | Сохранение / действие |
|---|---|---|
| Оба NULL | NONE | Снимок сохраняется; review не нужен. |
| Только валидный plan, в прошлом/сегодня/будущем | Пока NONE, предложение DAY в migration-review metadata | Не повышать план до жёсткого срока без подтверждения. Иначе возникнет новая просрочка и изменится смысл данных. |
| Только deadline | TIMED с тем же instant, origin zone неизвестна | Не округлять и не подменять date-only. Сохранить отметку неизвестного намерения выбора времени. |
| Обе даты; день совпадает в зоне просмотра | TIMED из deadline | Plan сохраняется отдельно как legacy snapshot; совпадение не доказывает дубль. |
| Обе даты; день отличается | TIMED из deadline, plan требует review | Не выбирать min/max и не сдвигать существующий deadline. Показать конфликт в сценарии разбора перехода. |
| Deadline в 18:00 / 00:00 | Всё ещё TIMED | Не делать вывод, выбирал ли пользователь время. Возможное преобразование в DAY — только явное действие. |
| Unix-text, пустой либо неверный ISO plan | NONE без deadline, иначе TIMED из deadline | Raw сохраняется, INVALID_PLAN/review; не угадывать дату и не нормализовать Feb 30. |
| Неизвестный формат/невозможный источник/неподдерживаемый instant | Без автоматического cutover этой записи | Диагностика и сохранение raw, не сброс и не тихое принятие. |

Пример timezone-конфликта: deadline `2026-10-19T00:30Z` относится к 18 октября в UTC−11 и к 19 октября в UTC+14, а plan `2026-10-19` не меняется. Поэтому совпадение полей не является основанием их объединить/удалить, и origin zone нельзя «восстановить» из текущей зоны устройства.

Review — метаданные перехода, **не второе редактируемое поле «План» в новом UI и не новое состояние задачи**. Для plan-only требуется отдельное явное решение пользователя: принять предложенный DAY, выбрать другой срок или оставить NONE. До этого нельзя молча включать новый UI и прятать прежнее планирование. Cutover должен быть заблокирован до review либо явного согласия на перенос предложений без срока. Конкретный UX review пока не реализован.

## 6. Повторное применение и безопасность PR 3

Предложение, проверенное чистой test-only функцией:

1. Сначала dry-run по raw SQLite, включая нераспарсенные значения, без записей.
2. У каждой строки отдельная версия conversion/source snapshot. Первый проход создаёт snapshot и canonical due атомарно.
3. Повтор с тем же исходником ничего не меняет. После пользовательской очистки/редактирования canonical не пересоздаётся из legacy.
4. Если пришла другая legacy запись, canonical и исходный snapshot сохраняются, все различные входящие raw-пары накапливаются в pending review без перезаписи предыдущих. Для production дополнительно нужны revision/op identity и причинная история конкурирующих событий.
5. Ни today, ни текущий timezone, ни время запуска миграции не меняют сохранённые instant/plan. Нельзя вычислять новые сроки от «сегодня».
6. В PR 3: одна транзакция на согласованную единицу миграции, rollback при сбое, version/schema checks, интеграционные crash/retry/reopen-тесты. Старые поля/архивы/сырой snapshot не удаляются.

В PR 2 **не существует новой SQLite-миграции**, поэтому её atomicity/crash recovery ещё не доказаны. Проверены идемпотентность предложенного преобразования, повтор snapshot-copy и повтор restore существующего формата. Это разные утверждения.

## 7. Межверсионная совместимость и архивы

Подтверждённые тестами границы:

- Текущий snapshot и полный backup→restore сохраняют 24 комбинации raw legacy полей, включая числовую строку, неверную дату, пустую строку и milliseconds. Повтор copy/restore не меняет исходник и результаты.
- Текущий snapshot **теряет неизвестную будущую колонку**, несмотря на перенос user_version. Тест моделирует это колонкой `proposed_due_v1` только в in-memory БД; это не выбранная production-схема.
- Текущий `applyUserTask` с `INSERT OR REPLACE` тоже теряет такую будущую колонку при обычном обновлении старого клиента. Наличие новой колонки не даёт межверсионной безопасности.
- Это проверки текущего кода против синтетического future-shape, не испытание двух выпущенных версий нового протокола: нового клиента пока нет.

Рекомендуемый PR 3 gate:

1. Обновить вместе schema install/migration, repositories, обычный enqueue и bootstrap, inbound validation, snapshotter, manifests/inventory/checksum и restore routing. Проверять поддержку до потерьной staging copy.
2. Due v1 authoritative только после capability/version handshake. Клиент без поддержки не должен редактировать canonical-даты или перезаписывать их обычным старым upsert. Практическая политика: запрет смешанного write-sync до обновления клиентов; если handshake невозможен, cutover блокируется.
3. Альтернатива — отдельное версионированное хранилище/события due с причинностью и конфликтами, но оно требует отдельного дизайна; один sidecar без правил не решает stale updates. Бесконечный dual-write не предлагается.
4. В legacy-архиве нет due v1: importer сохраняет raw и применяет согласованную таблицу. В новом архиве есть explicit NONE/DAY/TIMED, source snapshot и conversion version; явный NONE после restore не запускает legacy fallback.
5. Неизвестные новые schema/manifest/due версии — отказ с сохранением источника и без публикации destination. Не делать «успешный» downgraded restore с потерянным сроком.
6. Автоматический экспорт DAY в старый deadline с 18:00/00:00 запрещён; это не lossless compatibility. Не обещать работу нового срока со старыми клиентами до соответствующих integration-тестов.

## 8. Фикстуры, проверки и результаты

Тестовый oracle находится **только в jvmTest**:

- `ProposedTaskDueContract.kt`: tagged union, строгий разбор, timezone/DST, правила Today/overdue, pure conversion, поле-map envelope. Production его не использует.
- `ProposedTaskDueContractTest`: 32 legacy-комбинации; exact timestamp/00:00/18:00; floating DAY; UTC−11/UTC+14; DST gap/overlap; timezone-конфликт двух старых дат; disjoint Today/overdue; идемпотентность, clear/edit и изменение raw; envelope round-trip/unknown/missing-vs-clear.
- `LegacyTaskDateStorageTest`: 24 строки через реальный snapshot/backup/restore с повтором; future-column loss; текущая нормализация невозможной даты.
- `TaskDateSyncContractTest`: incremental mismatch, потеря после title edit, bootstrap control, loss future-column при old upsert; opt-in correctness gate.

Локальные логи/XML и исходные hashes: `scratch/pr2-task-due-contract-20261019/`. Они не добавляются в Git. Полный suite, remote sync и реальные данные не использовались.

Обычные команды проверки:

```powershell
.\gradlew.bat :uhabits-core:jvmTest --tests '*ProposedTaskDueContractTest' --tests '*LegacyTaskDateStorageTest' --tests '*ContainerBackupRestoreTest.testFullBackupRestoreRoundtrip_userTasks' --tests '*UserTaskDomainTest' --tests '*SQLiteUserTaskRepositoryTest' --console=plain --quiet
.\gradlew.bat :uhabits-android:testDebugUnitTest --tests '*TaskDateSyncContractTest' --tests '*TaskSmartViewTest' --tests '*TaskCalendarEventsTest' --console=plain --quiet
```

Окончательные числа тестов и статус — в локальном `RESULTS.md` рядом с XML. Известный opt-in red gate отдельно сохранён до очередного запуска, чтобы зелёные characterization-тесты не подменяли свидетельство дефекта.

## 9. Решения до реализации

Нужно принять: conservative plan-only review; TIMED группируется в текущей viewing zone; прошедший TIMED сегодня показывается только в отдельной просрочке; legacy 18:00 сохраняется как время; capability gate для старых write-клиентов; отдельное исправление существующего sync.

До этих решений PR 3 не начинать. Следующий PR не должен автоматически менять TaskSmartView/UI, FocusSession, привычки, графики или общие разделы. Полноценный новый wire/backup/migration контракт ещё требует реализации и интеграционных доказательств.
