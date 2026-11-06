# PR 2.1 — исправление существующего plannedDate sync

19.10.2026. Root подтверждён Git: текущий workspace `uhabits-next-dev`. HEAD остаётся `c6df44ec1ed371a679f348c0d233acf5b4ad9a45` (PR 1).

## Изоляция без коммитов

PR 2 подготовлен отдельным staged-набором ровно из 7 файлов, включая адресно `git add -f -- docs/TASK_DUE_CONTRACT_PR2.md`. Глобальный .gitignore не менялся. До PR 2.1 сохранены binary patch и ID index tree в `scratch/pr21-planned-date-sync-20261019/`. После этого индекс не изменялся. `.codex/`, scratch, базы, логи и evidence не staged.

`git diff --cached` — только PR 2. `git diff` — изменения PR 2.1 к этой базе, плюс новые untracked codec/test и этот документ. Общие тестовые файлы имеют staged-версию PR 2 и unstaged-версию PR 2.1. Обычный `git commit` зафиксирует только подготовленный PR 2, однако разрешение на него ещё не дано; **не использовать `git commit -a`**, иначе разделение будет нарушено. Ни PR 2, ни PR 2.1 не закоммичены. PR 3 не начат.

## Production diff

- `SyncManager.enqueueUserTask`: исходящий существующий plannedDate — ISO `YYYY-MM-DD`, как SQLite и bootstrap до ошибки. Дедлайн сериализуется прежним способом.
- `TaskPlannedDateCodec`: читает существующий ISO прежним парсером, дополнительно распознаёт точный исторический numeric формат. Это codec старого поля, не NONE/DAY/TIMED и не новая модель.
- `SQLiteUserTaskRepository`: совместимое чтение уже сохранённых numeric-строк, включая восстановленные из backup. На чтении записи не меняются; обычный последующий save сохраняет корректную ISO-дату вместо null.
- `SyncCoordinator`: recognised numeric при приёме/выгрузке bootstrap переводится в ISO. Исторический parseable ISO-текст не переписывается и не нормализуется дополнительно. `deadline`/status/completion и их сравнения не меняются.
- Backup/restore production код не менялся: raw-копирование старых колонок сохраняется, восстановленный numeric читает совместимый repository.

Нет изменений UI, привычек, графиков, FocusSession, SourceRules, контейнеров, DDL, schema version и миграций. Не запускались реальные DB migration, устройства или network sync.

## Почему numeric можно преобразовать без timezone-эвристики

Старый producer: `LocalDate.unixTime = 946684800000 + daysSince2000 * 86400000`. Это UTC epoch-day, а не local midnight; допустимое значение точно кратно 86400000, включая ноль и отрицательные значения.

Поэтому обратное преобразование — целочисленные дни, без Calendar/default TimeZone. Деление выполняется до вычитания, что исключает Long overflow. Перед narrowing проверяются границы календаря 1600-01-01…9999-12-31: LocalDate не поддерживает корректное обратное calendar-преобразование до 1600. Числа за этими границами, некратные суткам, переполненные, decimal/scientific значения не округляются и не угадываются.

`1792393200000` не является результатом старого producer. Его интерпретация как local midnight или внутридневного instant неоднозначна, поэтому тест требует отказ, сохраняя существующую строку. Для корректного исторического формата контрпример потери календарного дня не найден.

Сохранено старое поведение ISO-парсера. Например `2026-02-30` интерпретируется моделью как 02.03.2026, как до PR 2.1, но receive/bootstrap сохраняют исходный raw `2026-02-30`. Строгая переоценка таких legacy-данных остаётся вне исправления.

## Доказательства

Обязательный `regressionGate_incrementalRoundTripMustPreservePlannedDate` сначала запущен без opt-in на исходном production-коде и упал:

```
expected:<LocalDate(2026-10-19)> but was:<null>
```

XML сохранён до последующих запусков. После исправления gate проходит как обычный `@Test`, без env/Assume/Ignore.

Проверены:

- ISO incremental receive → repository → title-only edit → resend; bootstrap;
- JSON number и numeric string старого формата, повторный приём, отрицательный epoch day и ноль;
- уже сохранённая numeric SQLite-строка: bootstrap не мутирует source, title-only edit сохраняет дату;
- полный `runSync` двух in-memory устройств с fake backend, повторная отправка и явная очистка plan при сохранённом deadline;
- 5 timezones, DST dates, leap day, границы 1600/9999, независимый oracle java.time;
- 24 legacy-комбинации через snapshot/backup/restore, повторное восстановление и title-only edit после restore;
- deadline null, отрицательный, ноль, before/equal/after now и milliseconds; завершённая задача не становится overdue;
- malformed/non-day-aligned значения: отсутствие перезаписи исходной задачи;
- нестандартный исторический ISO raw: отсутствие новой нормализации receive/bootstrap.

Команды запуска и XML/точные числа: `scratch/pr21-planned-date-sync-20261019/RESULTS.md`. Android debug APK собран. Независимое read-only review numeric-декодирования не обнаружило оставшихся actionable замечаний.

## Ограничения и старые клиенты

1. Старый клиент по-прежнему может сначала потерять свою локальную дату, а затем отправить null. Такой payload неотличим от явной очистки; PR 2.1 не восстанавливает уже утраченные даты из истории или облака.
2. Новая версия принимает старый корректный numeric и отправляет ISO, который понимает старый ISO-reader. Это не устраняет собственную ошибку старого клиента при приёме numeric от другого старого клиента.
3. Неизвестные будущие поля всё ещё могут теряться при старом INSERT OR REPLACE или snapshot. Новый Due-контракт в production не добавлен; capability gate / mixed-version protection обязательны до PR 3.
4. Reliability hardening: неподдерживаемый raw из SQLite доступен как неперсистентная диагностика `UserTask.unsupportedPlannedDateRaw`. Задача остаётся видимой; обычные правки, completion, reorder и saveAll сохраняют raw. Выбор новой даты или явная очистка снимает диагностику. В списке и существующем поле даты есть предупреждение; picker объясняет локальное сохранение изменений до исправления. Схема и backup format не меняются.
5. Поддержка backward compatibility проверена offline через настоящие production serializer/inbound/repository и synthetic old-shaped payload; реальные выпущенные APK и облачный сервер не тестировались.

Никаких новых коммитов без отдельного разрешения пользователя.

## Проверка reliability hardening

- Воспроизведение до исправления: смешанный список с одной неподдерживаемой датой падает; XML сохранён в `scratch/pr21-planned-date-sync-20261019/hardening-red`.
- После исправления: 54 теста, 0 failures/errors/skips; повторены прежние 51 и добавлены 3 сценария. Android assembleDebug PASS.
- SQLite fixture: четыре корректные задачи и одна повреждённая; проверены все выборки, title edit, completion, reorder, saveAll, перенос в раздел и подзадача. Raw и deadline сохраняются.
- Backup/restore matrix расширена до 28 комбинаций; повторное восстановление и обычная правка повреждённой записи сохраняют исходный raw.
- Sync повреждённой задачи приостановлен: новые upsert не ставятся в очередь, старые pending upsert удерживаются, bootstrap пропускает только эту строку. Остальные задачи синхронизируются. После явной очистки корректный upsert снова проходит.
- Входящий некорректный payload отклоняется до записи; valid/null upsert также не перезаписывает локальную повреждённую запись до её явного исправления. Отложенный event удерживает общий pull cursor по существующему механизму skipped events; это ограничение требует исправить дату для полного продвижения sync. Удаление задачи сохраняет существующую семантику.
- Старые повреждённые queued payload не удаляются автоматически и могут оставаться pending после исправления; новая корректная запись отправляется. Автоматической реконструкции исходного дня нет.
- Канонический ISO producer применяется к новым операциям и bootstrap. При отправке уже существующей очереди создаётся transport copy с ISO вместо корректного legacy numeric; queue_id/op_uuid, порядок, timestamp, deadline и ACK сохраняются. Persisted payload не переписывается; повторная попытка строит ту же каноническую копию.
- ADB доступен, подключённых устройств нет. Native/визуальная проверка не выполнена; JVM и сборка её не заменяют.

## Два финальных риска — проверка 2026-10-19

### Исходящая legacy-очередь: исправлено

`pendingLegacyPayloadsAreCanonicalizedOnlyForTransportWithStableIdentityAndAcknowledgments` сначала упал на JSON number 0 вместо ISO string. Тест подтверждает, что исходный numeric не читается старым `parseIsoDateString`, тогда как transport ISO восстанавливает исходный день. Покрыты JSON number и numeric string, отсутствие ACK, повторный push, сохранение порядка/queue_id/op_uuid/прочих полей, корректный ACK по исходным queue_id и неизменность payload в SyncQueue даже после подтверждения. Production изменён только на границе передачи списка в backend.

### Входящий pull cursor: воспроизведено, НЕ исправлено

Добавлены два characterization-теста (проходят, фиксируя дефект, а не доказывая его устранение):

1. `knownRisk_malformedIncomingTaskPinsCursorAndStarvesLaterPages`: backend возвращает одну запись на страницу. На log_id=1 находится unsupported planned_date, далее корректные task/habit/container. Три runSync заканчиваются Failure, запрашивают cursor `[0,0,0]`, каждый получает только `[1]`; ни одна последующая сущность не загружается. Некорректная новая задача не создаётся. Исправление на источнике новым событием само по себе не помогает, если клиент не может дойти до него.
2. `knownRisk_deferredTaskAllowsSamePageButPinsLaterPagesAndIsRetriedAfterRepair`: первая страница содержит конфликт с локальным raw и корректную задачу. Корректная задача применяется; cursor остаётся `[0,0,0]`; habit/container на следующей странице недоступны. После явного локального исправления и повторного получения отложенного события следующая страница становится доступной. Тест искусственно оставляет локальный updated_at старше remote, чтобы доказать применение remote, а не stale-event no-op.

Backend production использует `log_id > cursor`, ascending; клиентского page limit нет, но сервер может ограничивать ответ. `runSync` делает один pull. Malformed task бросает exception и прерывает apply; deferred task возвращает false и удерживает durable cursor перед событием. Постоянного входящего inbox/retry store нет. Успех остальных событий в той же странице зависит от dependency sorting; это не гарантирует доступ к следующим страницам.

Безопасные варианты для отдельного согласования:

| Вариант | Повторная обработка | Цена и риски |
|---|---|---|
| Row-local отказ + scan-ahead с временным fetch cursor; durable cursor остаётся перед первым отказом | После crash/restart снова запрашивается исходный event из server log; ничего не считается окончательно пропущенным | Повторный обход всего хвоста, нагрузка и replay всех сущностей; нужен аудит идемпотентности/порядка и времени выполнения. Фиксированный лимит страниц без дополнительного fair scheduling снова допускает starvation. Нужна гарантия retention server log |
| Durable inbox отложенных событий до продвижения основного cursor (предпочтительно для устойчивого production-решения) | Полный исходный event/op_uuid хранится локально до успешного apply/явного разрешения; crash-safe replay | Новое состояние протокола: атомарность inbox/checkpoint, дедупликация, порядок конфликтов одного entity, crash/restart, account/dataset/reset и backup/restore. Выходит за минимальное исправление PR2.1; требует отдельного проектирования и разрешения |

В этом PR ни один вариант не реализован. Cursor не продвигается через ошибку без механизма повторной обработки. Окончательная готовность sync по второму сценарию не заявляется. Проверки после исправления первого риска: 31 Android JVM test PASS, assembleDebug PASS; 26 core tests из предыдущего запуска остаются применимыми (core не менялся), итого 57 проверок с учётом трёх новых тестов. Облако/реальные старые APK не использовались.
