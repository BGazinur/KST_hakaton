# Исходный план из ChatGPT

Это приложение к переписке. Здесь сохранён первоначальный план с предложениями. Состояние текущего прототипа и требования зафиксированы в [REQUIREMENTS.md](../REQUIREMENTS.md).

# Технический план Android AR + AI на 7 дней

С учётом твоих требований я предлагаю делать приложение на Kotlin + CameraX + ARCore + OpenCV + TensorFlow Lite, с локальным запуском моделей непосредственно на смартфоне.

Цель — получить Android-приложение, которое:

- Работает с камерой смартфона.
- Распознаёт несколько типов промышленного оборудования.
- Определяет отдельные узлы и визуальные признаки дефектов.
- Показывает выделенные области прямо на изображении.
- Использует IMU для пространственного отслеживания.
- Выводит AR-карточки с информацией об оборудовании.
- Работает без постоянного подключения к серверу.

Сразу обозначу техническое ограничение: универсальное распознавание любых промышленных объектов и надёжная диагностика всех неисправностей за неделю невозможны. Поэтому MVP будет поддерживать несколько классов оборудования и ограниченный набор визуальных дефектов, но архитектуру сделаем расширяемой.

## 1. Архитектура приложения

Android Application

Kotlin · Jetpack Compose

CameraX

Камера и кадры

ARCore + IMU

Положение телефона

AI Engine — локально

TensorFlow Lite / LiteRT · YOLO · классификатор

OpenCV

Обработка изображений

Room / SQLite

База оборудования

AR Visualization

Bounding boxes · маркеры · карточки · 3D-аннотации

### Почему именно такой стек

| Технология               | Назначение                                       |
| ------------------------ | ------------------------------------------------ |
| Kotlin                   | Основной язык                                    |
| Jetpack Compose          | Интерфейс                                        |
| CameraX                  | Работа с камерой                                 |
| ARCore                   | Пространственное отслеживание                    |
| OpenCV                   | Обработка кадров и сопоставление изображений     |
| LiteRT (TensorFlow Lite) | Локальный запуск моделей                         |
| YOLO                     | Детекция объектов                                |
| Room                     | Локальная база данных                            |
| ML Kit                   | Дополнительное распознавание текста и маркировок |

Важное решение: не запускать тяжёлую VLM на каждом видеокадре. На смартфоне лучше использовать быструю детекцию, а более сложный анализ делать по отдельному снимку. Для первой версии можно обойтись вообще без VLM.

## 2. Какие модели использовать

Для MVP предлагаю разделить распознавание на три задачи.

Модель №1 — распознавание оборудования

[Anwendungen – VEM Group](https://images.openai.com/static-rsc-4/Wbq5WWUXqs7bGAeB_OzwfJ6pCMa3JiDTtwW80DtRm0yDpHH2wjstDj9_KwHhgcjxbhrkgSOgSWYX2jAxbeicncuwTM0OWJbk01YnaRluWoYJZVwDQ1ArR34nyD9uJlonUJlNztUTdgH2cmdcWeZi7r7chPJuvBsu1IGvDPxQjK0?purpose=inline)

YOLO — Object Detection

Определяет тип оборудования: двигатель, насос, компрессор, вентиль, трубопровод.

Основная модель MVP

Модель №2 — определение дефектов

[a crack in a rusted metal surface with an opening in the middle](https://images.openai.com/static-rsc-4/ihAXMgXB0_D53-RUMxX6WjjuIxawWdeZJCVb3dhSismD9JacjQSv8iL9WcpvTue9zqFOxNvnDMRBfNBv0QYDbp5l6kwW0TV6mpXr_b3xC7deG360j9p2IHnjwmYGTDmMfHI5VzL6FP9x1RMF1UgioYVfnGt5cru3V5V6x5gwbDxgvuvdpzzfERzkX6sz7HXL?purpose=inline)

YOLO / классификатор изображений

Определяет ограниченный набор видимых признаков:

- Коррозия.
- Трещины.
- Повреждения поверхности.
- Утечки, если они визуально заметны.

Модель №3 — поиск похожих случаев

[Service-relevante Schaltpläne gehören oft in die Anleitung – nicht aber die ganze CE-Akte | docks](https://images.openai.com/static-rsc-4/H2DtJ7DMPsLM-UnjgFYDJVx6kxPc7CN5486xj2jR0EAlvBh5A3zwu7oAcbhci6-WzdPeNX7w3WgMYR_vqd4PIGsUn0eBruIDnr2KjwfaRqUYWCPy7592TrKtBUPfg1WA7lBbEpjoFPRcVeIGzYQc_GyyD_p6EoYtJg37e342tZU?purpose=inline)

Image Embeddings + локальная база

Сравнивает признаки изображения с заранее сохранёнными примерами неисправностей и выводит подходящие рекомендации.

### Важный момент по моделям

Не нужно обучать всё самостоятельно за неделю.

Я предлагаю следующую стратегию:

1. Взять готовую модель детекции объектов.
2. Экспортировать её в совместимый мобильный формат.
3. Подключить локальный inference.
4. Создать небольшой каталог оборудования.
5. Для дефектов использовать демонстрационный набор размеченных изображений.
6. Проверить качество на реальных фотографиях.

Для универсальности можно использовать открытый набор классов объектов, но дефекты нужно рассматривать как отдельную задачу: модель, обученная распознавать обычные предметы, не становится автоматически моделью промышленной диагностики.

## 3. Структура Android-проекта

Я бы сразу заложил модульную архитектуру, чтобы впоследствии можно было добавлять новые модели и типы оборудования.

```
IndustrialAR/
│
├── app/
│   ├── src/main/
│   │   ├── java/com/industrialar/
│   │   │
│   │   ├── MainActivity.kt
│   │   ├── IndustrialARApplication.kt
│   │   │
│   │   ├── camera/
│   │   │   ├── CameraManager.kt
│   │   │   ├── CameraAnalyzer.kt
│   │   │   └── FrameProcessor.kt
│   │   │
│   │   ├── ar/
│   │   │   ├── ARSessionManager.kt
│   │   │   ├── ARTracking.kt
│   │   │   ├── AROverlay.kt
│   │   │   └── ARObjectAnchor.kt
│   │   │
│   │   ├── ai/
│   │   │   ├── AIEngine.kt
│   │   │   ├── ObjectDetector.kt
│   │   │   ├── DefectDetector.kt
│   │   │   ├── ImageClassifier.kt
│   │   │   └── ModelManager.kt
│   │   │
│   │   ├── vision/
│   │   │   ├── OpenCVProcessor.kt
│   │   │   ├── ImagePreprocessor.kt
│   │   │   └── ImageMatcher.kt
│   │   │
│   │   ├── database/
│   │   │   ├── AppDatabase.kt
│   │   │   ├── EquipmentEntity.kt
│   │   │   ├── DefectEntity.kt
│   │   │   └── EquipmentDao.kt
│   │   │
│   │   ├── diagnostic/
│   │   │   ├── DiagnosticEngine.kt
│   │   │   ├── RecommendationEngine.kt
│   │   │   └── InspectionResult.kt
│   │   │
│   │   └── ui/
│   │       ├── CameraScreen.kt
│   │       ├── EquipmentCard.kt
│   │       ├── DefectCard.kt
│   │       ├── InspectionScreen.kt
│   │       └── ARControls.kt
│   │
│   ├── assets/
│   │   ├── models/
│   │   │   ├── equipment_detector.tflite
│   │   │   └── defect_detector.tflite
│   │   │
│   │   ├── labels/
│   │   │   ├── equipment_labels.txt
│   │   │   └── defect_labels.txt
│   │   │
│   │   └── database/
│   │       └── equipment_catalog.json
│   │
│   └── AndroidManifest.xml
│
├── build.gradle.kts
└── settings.gradle.kts
```

Это логическая структура MVP. На практике `assets` с моделями лучше разместить в `app/src/main/assets/`, а исходники — в `app/src/main/java/`.

### Как будут взаимодействовать модули

| Модуль           | Получает                      | Возвращает                |
| ---------------- | ----------------------------- | ------------------------- |
| CameraAnalyzer   | Кадр камеры                   | Изображение               |
| ObjectDetector   | Изображение                   | Тип объекта, координаты   |
| DefectDetector   | Изображение / область объекта | Тип признака, координаты  |
| ImageMatcher     | Изображение / признаки        | Похожие записи            |
| DiagnosticEngine | Результаты моделей            | Структурированный диагноз |
| AROverlay        | Координаты и результат        | Визуальные метки          |
| EquipmentCard    | Данные оборудования           | Карточку на экране        |

Важно, чтобы `CameraAnalyzer` не запускал тяжёлые операции в основном UI-потоке. Обработку кадров следует выполнять асинхронно, ограничивая очередь кадров и отбрасывая устаревшие.

## 4. План разработки по дням

Это наиболее важная часть. Предлагаю не тратить неделю на создание отдельных красивых модулей, а каждый день получать работающий результат.

День 1 — запуск Android-приложения

Фундамент

[How to setup a camera in an Android app using Jetpack Compose](https://images.openai.com/static-rsc-4/arRUPYB4MuzgPQAeuoVoSMjaGP17VQYcc2MXvcSJTIpgFRKd_-x697_LlIoIruWe2vnHB5EpGoHbMhgsiyCrKrU17eIvWK8BW0rSt_ftQX5l5m1bj9cvZ3At7GW66Zyd4BZKJDqv9-3cuBatFhv7hgRJZ6bW7xYMAPkl9o-TkZI?purpose=inline)

[delasign.com](https://www.delasign.com/blog/android-studio-jetpack-compose-kotlin-camerax-composable/?utm_source=chatgpt.com)

Задачи:

- Создать проект в Android Studio.
- Настроить Kotlin и Jetpack Compose.
- Подключить разрешения камеры.
- Реализовать экран камеры.
- Получать фотографии и видеокадры.
- Проверить работу на физическом смартфоне.

Результат: приложение запускается, показывает изображение с камеры и умеет сохранять фотографии.

День 2 — компьютерное зрение

[Mastering Object Detection | alwaysAI Blog](https://images.openai.com/static-rsc-4/xBMSslQ-9l5NlVjE5UoCbDdM7CDBwSS3rssRPd7T9_AuJoD0MkU9_sh_FeU-301e93t0FBwL7KXJ6A5N5fXjAv86wemV6r4ft7t55nsBYDit02MbhCiU-BeMfF7jmpGBBs2lyZ2wb67IZY6h_jw8D11ntE6exZrYo_I-7LPUikc?purpose=inline)

[alwaysai.co](https://alwaysai.co/blog/using-object-detection?utm_source=chatgpt.com)

Задачи:

- Подключить OpenCV.
- Подключить LiteRT.
- Загрузить готовую модель детекции.
- Реализовать преобразование кадра в формат модели.
- Реализовать обработку результатов и координат рамок.
- Вывести результаты на экран.

Результат: приложение обнаруживает объекты и рисует рамки.

День 3 — промышленное оборудование и дефекты

[AI Vision Camera — Visual Inspection, Defect Detection & Safety Monitoring | iFactory AI](https://images.openai.com/static-rsc-4/hfgBK03yG08cthVMXPJ6U4VSLLhneKZ4AWQvP5qlE4jcq4gmzRzSmOxWlA7wInsKlhOMz-hieQZ49-0IFotuNdL9ycwoRXXdUwyW5fdsmVRCtgxlzgIZdJlk-mulXV7cjVro9w-93HW3jZF6R54VrY5ccbJk79rzkuLVZjNy3OM?purpose=inline)

[ifactoryapp.com](https://ifactoryapp.com/ai-vision-camera/?utm_source=chatgpt.com)

Задачи:

- Определить классы оборудования.
- Добавить каталог из нескольких типов агрегатов.
- Подключить модель определения видимых дефектов.
- Добавить классификацию подозрительных участков.
- Сделать единый формат результатов AI.

Результат: приложение распознаёт выбранные виды оборудования и показывает результаты анализа.

День 4 — база знаний и диагностика

[カミナシ、現場の従業員や教育管理、設備保全の領域へ進出 | 株式会社カミナシのプレスリリース](https://images.openai.com/static-rsc-4/hEVsDR60YOsoqEhDRceD2ttstDFjrai7m35tSSgApwyzAjYln8P7VcCBxxWqKTkSarq25FyWiN3Re1zEtaBwwpzwZTM5CdcSOjeTwcROn2lZq8AhJwzXofkOMxb_ha5Rbl1NN0ZKg3eRqn-99h6r55YwSDevsqZFviRSQOVx5PU?purpose=inline)

[prtimes.jp](https://prtimes.jp/main/html/rd/p/000000128.000054269.html?utm_source=chatgpt.com)

Задачи:

- Создать Room Database.
- Добавить каталог оборудования.
- Создать каталог дефектов.
- Добавить причины возникновения.
- Добавить рекомендации.
- Реализовать поиск похожих записей.

Результат: обнаруженный признак связывается с карточкой оборудования и рекомендациями.

День 5 — AR и IMU

[How Augmented Reality Can Support Industry 4.0   | Quality Magazine](https://images.openai.com/static-rsc-4/dyAa0kj8nxX5PexkIEgG64NwDTCNYrRHoox1GygFG-LFPoHxzNPlC5EXUs1WB5LFXRI5pwkeoS2Fq4KHy3JPcOo-exlF4ogfOPLOenNcJpwmRY-UQxbY3r8xKtvjxrJ6awY3ThvqB3x_PtLpsdh5Zq6e76mO6rCYWRoqHWm0UQs?purpose=inline)

[qualitymag.com](https://www.qualitymag.com/articles/96416-how-augmented-reality-can-support-industry-40?utm_source=chatgpt.com)

Задачи:

- Подключить ARCore.
- Проверить поддержку устройства.
- Получить положение камеры и данные трекинга.
- Создать AR-слой.
- Разместить маркеры на изображении.
- Протестировать стабильность меток при перемещении смартфона.

Результат: приложение отображает привязанные к изображению аннотации и пространственные маркеры.

День 6 — объединение

[Home · Deeploop Technologies](https://images.openai.com/static-rsc-4/RUcoMaR5z0h_JYH-auU1wP0yPkU5HNsegqfUexciA5nQ9FzKYTrDETbdgOPfq2ViO0wtRN_U7kLNXHJNIizZScvOru46HHJlAcaTTyotFLeIa8vimOXd_fO-DYIQYDEsqp8NT-CO3mCW_8c9ykpOJCPBDdJIKSpiazw-VptsDio?purpose=inline)

[deeploop.tech](https://deeploop.tech/?utm_source=chatgpt.com)

Задачи:

- Объединить камеру и AI.
- Объединить AI и базу знаний.
- Добавить карточки дефектов.
- Добавить подробную информацию.
- Добавить сохранение результатов осмотра.
- Оптимизировать частоту инференса.

Результат: полный сценарий диагностики работает внутри приложения.

День 7 — тестирование и демонстрация

[TeamViewer brings Windows AI API to Assist AR for clearer, smarter remote assistance to frontline workers](https://images.openai.com/static-rsc-4/n3qnSF_1qwiZMsprtc5t-zqlM3ryZn8CHWNfzqqlUswgrLay5zUf0ZArSWHK7aVYoTftOgEHKSLHxdaeotmV2Qqt3b61nApUtx_MR-e7xnwnMyoS1IcqUm-_vKbOJ_tijgcYl-9GxcZP0yvY7Ibi2K8Q6ybAc01rxbJeyTDLnBs?purpose=inline)

[prnewswire.com](https://www.prnewswire.com/news-releases/teamviewer-brings-windows-ai-api-to-assist-ar-for-clearer-smarter-remote-assistance-to-frontline-workers-302790117.html?utm_source=chatgpt.com)

Задачи:

- Проверить распознавание оборудования.
- Проверить правильность рамок.
- Проверить работу AR.
- Проверить работу без интернета.
- Измерить скорость обработки.
- Собрать APK.
- Подготовить демонстрационный сценарий.

Результат: установленный на смартфон MVP.

## 5. Важный нюанс: CameraX и ARCore

Есть архитектурный момент, который лучше решить в самом начале.

CameraX и ARCore оба работают с камерой, поэтому нельзя просто независимо запустить два полноценных потока захвата, рассчитывая, что Android всегда позволит им совместно использовать камеру.

Я рекомендую такой вариант:

- В обычном режиме диагностики использовать CameraX.
- В AR-режиме использовать ARCore как основной источник камеры и пространственных данных.
- Передавать доступные кадры ARCore в AI Engine для анализа.
- Использовать общий интерфейс `FrameSource`, чтобы AI Engine не зависел от конкретного источника кадров.

ARCore документирует получение изображений камеры и работу с сенсорами. CameraX, в свою очередь, предоставляет режим `STRATEGY_KEEP_ONLY_LATEST`, который особенно полезен при медленном AI-инференсе.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developers.google.com\&sz=32)

Google for Developers+1



Это избавит от необходимости постоянно передавать огромные изображения между несколькими компонентами.

## 6. Как должен выглядеть формат результатов AI

Все модели должны возвращать единый объект, чтобы интерфейс не зависел от того, какая именно нейросеть выполнила распознавание.

Например:

```
data class DetectionResult(
    val equipmentType: String,
    val equipmentConfidence: Float,
    val defects: List<DefectResult>,
    val timestamp: Long
)

data class DefectResult(
    val defectType: String,
    val confidence: Float,
    val boundingBox: BoundingBox,
    val description: String,
    val recommendations: List<String>
)

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
```

Координаты рамок лучше хранить нормализованными от 0 до 1, а перед отображением преобразовывать в координаты Preview/AR-экрана с учётом поворота и обрезки изображения.

## 7. Как будет выглядеть интерфейс

Предлагаю сделать четыре основных экрана.

[Librestream Introduces Expanded AI and AR Features to its Onsight Platform, Further Enhancing Knowledge Accessibility for Frontline Workforces](https://images.openai.com/static-rsc-4/ZuRQ1d_erQbiRZ7uOVhkqrXKfq6eDHsiXsH5jQWCkRdtK77VA0vK7rQhC72FLlii-EQFwB_Ed9OWULzVCqQ4hyLE3Oqsx0CZGktu8OrnYExRDDR07XlVHMDn-wzZaujQqLs6M_7-GZ4jg_N99nGptrRWTfrIxWZ0YTvasA7jndo?purpose=inline)

Экран 1. AR-сканер

- Камера в реальном времени.
- Обнаруженные объекты.
- Рамки дефектов.
- Кнопка сканирования.
- Индикатор качества распознавания.

[AI Vision Camera — Visual Inspection, Defect Detection & Safety Monitoring | iFactory AI](https://images.openai.com/static-rsc-4/F4iRrByc9FgHW7PBm4OnY_qnhShyCQdK5Ek6qBHjfKh6hgUinC-UkeYA9PyaV4a4qcCVArT7coyiHNihInSISHXeP5GJDJH8n5LBeJLk2EALXgy_h9UU3Ci70d7owwMUEN_GdkfeGr3YN3CH7_1b5Ru-rKVUxXigbhrChfqJBUI?purpose=inline)

Экран 2. Карточка оборудования

- Тип оборудования.
- Название и модель.
- Обнаруженные узлы.
- Известные дефекты.
- Состояние осмотра.

[Digital factory use case: Defect reporting with NavVis IVION Go](https://images.openai.com/static-rsc-4/1QZjyn1LOCbbkatSMAUTLZFzbRkxY6MFdIrPcCUSoc9dpLz0p70E8Dv2zbPfP8Eh4OGh970Ca8fj0-IE1-Ne62sCdFJIsSheVA4C3A2OlPwWqtOmmPylMd-ow5Y4anfLB13HsNtf8srTQ7HeChjNuDvo3RtaB5tR0QcDuGDczXs?purpose=inline)

Экран 3. Диагностика

- Предполагаемый дефект.
- Уверенность модели.
- Возможные причины.
- Рекомендации.
- Необходимость дополнительной проверки.

[Enterprise | MaintainX](https://images.openai.com/static-rsc-4/fyc8I3aZM6TPP_LfAbDUXQ7eEqMC7ml_77zHlZQsQM8utg7_VWHU07dAuaytK1jhbPe69xkqkL0LIzPvt3OW8eJfj1GGUBEyQ-GlKG54tO96F_Tckw1A3tkM3KA_VTltauSR8Ssfd168MgJcPtG7Zskr45AezekcmBHU67PviI4?purpose=inline)

Экран 4. История

- Предыдущие осмотры.
- Фотографии.
- Найденные дефекты.
- Результаты обслуживания.

## 8. Что потребуется для запуска

| Ресурс                      | Рекомендация                          |
| --------------------------- | ------------------------------------- |
| Компьютер                   | Windows / Linux / macOS               |
| IDE                         | Android Studio                        |
| Язык                        | Kotlin                                |
| Тестовый телефон            | Android с поддержкой ARCore           |
| Оперативная память телефона | Желательно 8 ГБ и более               |
| Интернет                    | Для первоначальной загрузки моделей   |
| Сервер                      | Не требуется                          |
| GPU                         | Не требуется для мобильного инференса |

Для локальных языковых моделей Google предлагает LiteRT-LM и мобильные варианты Gemma, но я бы пока не добавлял полноценную LLM: она увеличит объём приложения и требования к памяти. Сначала реализуем детекцию и поиск по локальной базе.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://ai.google.dev\&sz=32)

Google AI for Developers+1



## 9. Критерии готовности MVP

К концу недели предлагаю считать проект успешным, если выполнены следующие условия:

| Проверка           | Критерий                                                                     |
| ------------------ | ---------------------------------------------------------------------------- |
| Запуск             | APK устанавливается и запускается                                            |
| Камера             | Работает без ошибок                                                          |
| Распознавание      | Не менее 4–5 классов оборудования                                            |
| Дефекты            | Не менее 2–3 обученных визуальных классов                                    |
| AI                 | Инференс выполняется локально                                                |
| AR                 | Отображаются метки и рамки                                                   |
| IMU                | Работает пространственное отслеживание                                       |
| База               | Доступна без интернета                                                       |
| Диагностика        | Показываются рекомендации                                                    |
| Производительность | Целевой ориентир 5–10 анализируемых кадров в секунду на выбранном устройстве |

Последний показатель — цель для оптимизации, а не гарантированная скорость. Она будет зависеть от конкретного смартфона и размера модели.

## 10. С чего предлагаю начать прямо сейчас

Я бы не начинал с написания всех модулей сразу. Сначала создадим минимальный каркас проекта и проверим наиболее рискованные технологии.

Первый этап — создать рабочий Android-проект:

1. Android Studio.
2. Kotlin.
3. Jetpack Compose.
4. CameraX.
5. OpenCV.
6. ARCore.
7. LiteRT.

Полезные официальные ресурсы:

- [CameraX](https://developer.android.com/media/camera/camerax?utm_source=chatgpt.com).
- [ARCore](https://developers.google.com/ar/develop?utm_source=chatgpt.com).
- [LiteRT](https://ai.google.dev/edge/litert?utm_source=chatgpt.com).
- [OpenCV](https://opencv.org/?utm_source=chatgpt.com).
- [Room Database](https://developer.android.com/jetpack/androidx/releases/room?utm_source=chatgpt.com).

Но перед началом разработки мне важно узнать одну вещь:

На каком смартфоне будем тестировать приложение?

Samsung Galaxy

Google Pixel

Xiaomi / Redmi

OnePlus

Другой Android

Пока нет смартфона

Модель телефона (если знаешь)

Среда разработки

Android Studio уже установлен

Нужно установить Android Studio

Есть опыт Android-разработки

Начать создание проекта

Моя идея — в итоге получить не макет, а устанавливаемый APK, который мы сможем постепенно превратить в полноценную систему промышленной AR-диагностики.
