ИДЕНТИФИКАЦИЯ ОБОРУДОВАНИЯ: QR / ЦИФРОВОЙ КОД → ПОИСК В БД

Согласованный демо-формат: 8 ASCII-цифр, пример 00123456.
Это equipmentCode, строковый ключ для поиска. Это НЕ название оборудования.
Название и модель возвращаются из БД. Ведущие нули сохраняются:
00123456 и 123456 — разные значения. CodeRules(digits=8) настраивается.
Код должен обозначать одну запись в вашем каталоге. Если это номер модели,
он не доказывает идентичность конкретного физического экземпляра насоса.

СОСТАВ И ПОДКЛЮЧЕНИЕ

Наложение на модуль cv из PUMP_CV_TEAM_HANDOFF.zip:
src/main/ → cv/src/main/
src/test/ → cv/src/test/
В dependencies cv/build.gradle.kts добавить:
 implementation("com.google.mlkit:barcode-scanning:17.3.0")
 implementation("com.google.mlkit:text-recognition:16.0.1")
Использованы bundled ML Kit модели: доступность распознавания не требует
предварительной загрузки модели через Google Play. Репозиторий google().
ONNX и их SHA/версия не изменяются: QR/OCR работает отдельным этапом.
При изменении длины кода обновить CodeRules и pattern/minLength/maxLength
в JSON Schema; текущие файлы согласованы для восьми цифр.

ГОТОВЫЙ ВХОД ИЗ ARCORE

ArCoreEquipmentCodeBridge переиспользует Yuv420Copier существующего модуля.
Он принимает текущий Frame от вашего Session, копирует planes на GL и сразу
закрывает Image. QR/OCR и голосование выполняются на одном отдельном worker.
AR-трекинг/3D-поза для чтения кода не требуются. beginScan/onFrame/invalidate
вызывать на одном GL-потоке, takeDecision можно опрашивать на UI.

 val idBridge = ArCoreEquipmentCodeBridge(CodeRules(digits = 8))
 // Один раз при начале идентификации на render thread:
 idBridge.beginScan(sessionId, inspectionEpoch)
 // После session.update(), только в фазе идентификации:
 idBridge.onFrame(frame, sessionId, inspectionEpoch, capturedRotationCw, roi)
 // На UI, с текущими значениями session/epoch:
 val request = idBridge.takeDecision(sessionId, inspectionEpoch)?.decision?.request
 request?.let { publishEquipmentLookup(it.wireFields()) }
 // Pause/смена области сканирования: invalidate на GL, новый epoch/beginScan.
 // Destroy: idBridge.close(), Session остаётся собственностью приложения.

В этой фазе использовать idBridge вместо onFrame основного CV-моста, чтобы
не выполнять две копии CPU Image и YOLO одновременно. После выдачи запроса
idBridge перестаёт принимать кадры до нового beginScan. Пока идёт lookup,
приложение хранит pending requestId и не начинает анализ неизвестного агрегата.
После точного ответа каталога перейти к существующему ArCoreCvBridge и
профилю выбранного оборудования. Этот адаптер даёт код, не определяет паспорт.
Рабочий URL backend в этой поставке не задан.

ПОРЯДОК РАБОТЫ

Готовый ArCoreEquipmentCodeBridge выполняет пункты 3–7 сам. Прямой вызов
EquipmentCodeScanner нужен только если приложение уже имеет собственный
поток CvFrame. Это альтернативные способы входа, не два параллельных reader.
Русские пользовательские строки приложены в src/main/res/values/.

1. UI запускает режим идентификации и показывает рамку для одного кода.
2. Приложение увеличивает inspectionEpoch и очищает прежний equipmentId,
   незавершённый lookup, старые CV-результаты, точки и якоря осмотра.
3. На выделенном worker создать EquipmentCodeScanner и EquipmentCodeGate.
   Вызвать gate.beginScan(sessionId, inspectionEpoch) с текущей областью осмотра.
   При изменении рамки сканирования начать новый scan/epoch, чтобы не смешивать
   голоса от разных областей. Нельзя автоматически переключать scope по
   позднему результату старого кадра.
4. Передавать существующий CvFrame из RAM и CodeScanRegion. Scanner.scan()
   блокирующий: вызывать только на worker, не на GL/UI. Один кадр в обработке,
   новые кадры пропускать, очередь не накапливать. Не запускать YOLO, пока
   идентификация не завершена. Не открывать вторую камеру / CameraX поверх ARCore.
5. Рамка CodeScanRegion задаётся в нормализованных координатах UPRIGHT CPU
   изображения, не экрана. Преобразование экранной рамки делать через захваченную
   геометрию текущего кадра. Scanner сам поворачивает RAW RGB по rotationCw и
   пересекает рамку с validCrop. Не уменьшать код до YOLO-входа 416 ради OCR.
6. Сначала QR; если QR не найден — OCR строк внутри выбранной области.
   Поддержан QR с чистыми цифрами. URL/JSON, O→0, склейка чисел и извлечение
   цифрового суффикса не выполняются. Несколько разных кодов → AMBIGUOUS.
   Найденный, но не декодированный/невалидный QR не подменяется фоновым OCR.
7. observation передать gate.observe(). Три одинаковых QR-чтения или пять
   OCR-чтений из разных последовательных кадров, с промежутком не более
   750 мс, дают CODE_READY и EquipmentLookupRequest. Это устойчивость чтения,
   а не доказанная вероятность правильности; систематическая ошибка OCR возможна.
8. CV отдаёт request.wireFields() приложению. Один запрос на scan; дальнейшие
   кадры дают ALREADY_EMITTED. Сам CV-модуль не устанавливает соединение с БД.
   API/репозиторий приложения отправляет equipmentCode в backend/локальный каталог.
9. Ответ БД применять только если requestId, sessionId и inspectionEpoch всё
   ещё соответствуют текущему запросу. Не найдено → показать неизвестный код;
   больше одной записи → неоднозначность/ошибка каталога. Не брать первую запись.
   Один точный результат → сохранить equipmentId/modelId/профиль осмотра,
   отобразить название, затем запускать CV-осмотр для этого оборудования.
   Перед осмотром оператор должен видеть выбранный паспорт оборудования.
10. При паузе/смене оборудования gate.reset(), очистить pending lookup и
   инвалидировать epoch. beginScan() обязателен перед следующей попыткой.
   scanner.close() выполнять на том же worker после окончания scan.

Минимальный вызов на worker:

 val scanner = EquipmentCodeScanner(CodeRules(digits = 8))
 val gate = EquipmentCodeGate()
 gate.beginScan(currentSessionId, currentInspectionEpoch)
 // Для каждого принятого кадра, только в режиме идентификации:
 val observation = scanner.scan(cvFrame, roiInUprightCpuImage)
 val decision = gate.observe(observation)
 decision.request?.let { request ->
     // передать приложению; не выполнять SQL в CV и не выдумывать equipmentName
     publishEquipmentLookup(request.wireFields())
 }

CvFrame должен поступать из уже закрытого ARCore Image через копию YUV/RGB
в RAM. Scanner не записывает изображения и не создаёт снимков. Область
сканирования должна охватывать сам идентификатор, чтобы не читать числа
манометра или характеристики шильдика вместо кода. Если в рамке осталась
одна посторонняя строка из восьми цифр, один только OCR не знает её назначения.

КОНТРАКТ С БД

Минимальное тело поиска:
 {"equipmentCode":"00123456"}
Endpoint и фактическую структуру ответа определяет backend-команда; здесь
нет фиктивного URL или рабочего соединения. equipmentCode хранить как TEXT /
VARCHAR, обеспечить однозначное соответствие записи и согласованный формат.
Не приводить к integer, не срезать нули, не сопоставлять по подстроке.
requestId — корреляция/дедупликация в приложении; это не аутентификация.
После перехода к другому коду старый HTTP-ответ не должен менять оборудование.

schema/equipment-code-event.schema.json — внутреннее CV-событие приложения.
examples/ — внутреннее событие и минимальное тело поиска.
Текущий контракт CvResult сегментации не менялся: идентификация — отдельное
событие перед выбором записи. Сам QR не определяет, какой из нескольких
насосов в кадре связан с паспортом: сохраняется выбор/уточнение агрегата.

Официальные зависимости и ввод изображений:
https://developers.google.com/ml-kit/vision/barcode-scanning/android
https://developers.google.com/ml-kit/vision/text-recognition/v2/android

Проверка на реальном телефоне ещё потребуется: QR, печатные цифры, блики,
повороты, изменение рамки, два кода, пауза и поздний ответ БД.
