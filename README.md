# HASD Lab 1

Лабораторная работа №1 по предмету «Хранение и алгоритмы сжатия данных».

## О работе

Программа реализует собственный бинарный колоночный формат для большого CSV-датасета LendingClub. Кодирование и декодирование выполняются потоково, без загрузки всего файла в память.

## Реализованные методы

- LEB128 и ZigZag;
- PLAIN, Delta и Frame of Reference;
- Gorilla XOR;
- Dictionary Encoding;
- Common Prefix;
- Scaled Integer;
- nullable bitmap;
- length-prefixed UTF-8.

## Запуск

Откройте `Main.java` в IntelliJ IDEA и нажмите Run. При первом запуске датасет скачается автоматически, затем программа выполнит:

`encode -> decode -> verify`

CLI также доступен:

```shell
./gradlew run --args="encode --input data/loan.csv --schema schema.json --output data/loan.hasd"
./gradlew run --args="decode --input data/loan.hasd --output data/loan.decoded.csv"
./gradlew run --args="verify --original data/loan.csv --restored data/loan.decoded.csv"
```
