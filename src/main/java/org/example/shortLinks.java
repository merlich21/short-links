package org.example;

import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class shortLinks {
    // Поля
    private final Map<String, UserData> users = new HashMap<>();
    private final Properties config = new Properties();
    private final Gson gson = new Gson();

    // Подгружаемые константы
    private String BASE_URL;
    private String DATA_FILE;
    private long MAX_EXPIRY_TIME_MS;
    private int DEFAULT_LIMIT_REDIRECT;

    private String currentUserUuid;

    // Методы для старта работы с программой
    public shortLinks() {
        loadConfig();  // Загрузка конфигурации
        loadData(); // Загрузка данных из файла
        removeExpiredLinks(); // Удаление устаревших ссылок
    }

    /**
     * Внутренние классы для хранения информации о ссылке
     */
    class UserData {
        private final String uuid;
        private final Map<String, LinkData> links;

        public UserData(String uuid, Map<String, LinkData> links) {
            this.uuid = uuid;
            this.links = links;
        }

        public String getUuid() {
            return uuid;
        }

        public Map<String, LinkData> getLinks() {
            return links;
        }
    }

    class LinkData {
        private final String longUrl;
        private final long expiryTime;
        private int visitLimit;

        public LinkData(String longUrl, long expiryTime, int visitLimit) {
            this.longUrl = longUrl;
            this.expiryTime = expiryTime;
            this.visitLimit = visitLimit;
        }

        public String getLongUrl() {
            return longUrl;
        }

        public long getExpiryTime() {
            return expiryTime;
        }

        public int getVisitLimit() {
            return visitLimit;
        }

        public void decrementVisitLimit() {
            this.visitLimit--;
        }

        public boolean isLimitReached() {
            return visitLimit == 0;
        }
    }

    /**
     * Позволяет получить данные текущего пользователя.
     */
    private UserData getCurrentUser() {
        return users.values().stream()
                .filter(user -> user.getUuid().equals(currentUserUuid))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден!"));
    }

    /**
     * Загружает настройки из файла конфигурации.
     */
    private void loadConfig() {
        try (InputStream input = new FileInputStream("config.properties")) {
            config.load(input);

            BASE_URL = config.getProperty("base_url", "chertchill.ru/");
            DATA_FILE = config.getProperty("data_file", "user_data.json");
            MAX_EXPIRY_TIME_MS = Long.parseLong(config.getProperty("max_expiry_time_ms", String.valueOf(TimeUnit.DAYS.toMillis(1))));
            DEFAULT_LIMIT_REDIRECT = Integer.parseInt(config.getProperty("default_limit_redirect", "5"));

            System.out.println("==================================================");
            System.out.println("Конфигурация загружена из config.properties:");
            System.out.println("Базовый URL - " + BASE_URL);
            System.out.println("Файл с данными - " + DATA_FILE);
            System.out.println("Максимальное время действия ссылки - " + formatRemainingTime(System.currentTimeMillis() + MAX_EXPIRY_TIME_MS));
            System.out.println("Лимит переходов по умолчанию - " + DEFAULT_LIMIT_REDIRECT);
            System.out.println("==================================================");
        } catch (IOException e) {
            System.err.println("Не удалось загрузить конфигурацию. Используются значения по умолчанию.");
            BASE_URL = "chertchill.ru/";
            DATA_FILE = "user_data.json";
            MAX_EXPIRY_TIME_MS = TimeUnit.DAYS.toMillis(1);
            DEFAULT_LIMIT_REDIRECT = 5;
        }
    }

    /**
     * Загружает данные пользователей из файла.
     */
    private void loadData() {
        try (Reader reader = new FileReader(DATA_FILE)) {
            Map<String, UserData> data = gson.fromJson(reader, new TypeToken<Map<String, UserData>>() {}.getType());
            if (data != null) {
                users.putAll(data);
            }
        } catch (FileNotFoundException e) {
            System.out.println("Данные не найдены, начата новая сессия.");
        } catch (IOException e) {
            System.err.println("Ошибка при загрузке данных: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Некорректный формат данных: " + e.getMessage());
        }
    }

    /**
     * Сохраняет данные пользователей в файл.
     */
    private void saveData() {
        try (Writer writer = new FileWriter(DATA_FILE)) {
            gson.toJson(users, writer);
        } catch (IOException e) {
            System.err.println("Ошибка при сохранении данных: " + e.getMessage());
        }
    }

    /**
     * Проверяет доступность URL.
     */
    public boolean isUrlAccessible(String urlString) {
        try {
            URI uri = URI.create(urlString);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)) // Таймаут подключения
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5)) // Таймаут запроса
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            int statusCode = response.statusCode();
            return statusCode >= 200 && statusCode < 400;

        } catch (IllegalArgumentException e) {
            System.out.println("Некорректный URL: " + urlString);
            return false; // Если URI некорректен
        } catch (Exception e) {
            return false; // Для любой другой ошибки
        }
    }

    /**
     * Удаляет просроченные ссылки с указанием исходной ссылки в сообщении.
     */
    public void removeExpiredLinks() {
        long currentTime = System.currentTimeMillis();

        users.forEach((username, user) -> {
            Iterator<Map.Entry<String, LinkData>> iterator = user.getLinks().entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, LinkData> entry = iterator.next();
                LinkData link = entry.getValue();

                if (link.getExpiryTime() <= currentTime) {
                    System.out.println("Срок действия ссылки " + entry.getKey() + " (" + link.getLongUrl() + ") истёк. Пользователь " + username);
                    iterator.remove();
                } else if (link.isLimitReached()) {
                    System.out.println("Лимит переходов по ссылке " + entry.getKey() + " (" + link.getLongUrl() + ") исчерпан. Пользователь " + username);
                    iterator.remove();
                }
            }
        });

        saveData();
    }

    /**
     * Позволяет парсить время действия ссылки в мультиформатном виде (1h, 1d, 1m и в комбинациях).
     */
    private long parseDuration(String input) {
        long totalMs = 0;
        String[] parts = input.split(" ");

        for (String part : parts) {
            try {
                if (part.endsWith("d")) {
                    totalMs += TimeUnit.DAYS.toMillis(Long.parseLong(part.replace("d", "")));
                } else if (part.endsWith("h")) {
                    totalMs += TimeUnit.HOURS.toMillis(Long.parseLong(part.replace("h", "")));
                } else if (part.endsWith("m")) {
                    totalMs += TimeUnit.MINUTES.toMillis(Long.parseLong(part.replace("m", "")));
                } else {
                    System.out.println("Неверный формат времени: " + part + ". Укажите значение, например, 1d 2h 30m.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Ошибка парсинга: " + part);
            }
        }

        return totalMs;
    }

    /**
     * Аутентифицирует пользователя или создает нового.
     */
    public void authenticate(Scanner scanner) {
        String username;

        while (true) {
            System.out.println("Введите ваш username для входа:");
            username = scanner.nextLine().trim();

            // Проверка, что имя пользователя состоит только из букв (латинские буквы)
            if (username.isEmpty() || !username.matches("[a-zA-Z]+")) {
                System.out.println("Имя пользователя должно состоять только из букв. Попробуйте снова.");
            } else {
                break; // Если имя корректно, выходим из цикла
            }
        }

        if (users.containsKey(username)) {
            currentUserUuid = users.get(username).getUuid();
            System.out.println("Добро пожаловать, " + username + "!");
            System.out.println("Ваш UUID: " + currentUserUuid);
        } else {
            UserData newUser = new UserData(UUID.randomUUID().toString(), new HashMap<>());
            users.put(username, newUser);
            currentUserUuid = newUser.getUuid();
            saveData();
            System.out.println("Новый пользователь создан.");
            System.out.println("Ваш UUID: " + currentUserUuid);
        }
    }

    /**
     * Преобразует исходный URL в короткий.
     * Для каждого запроса создается уникальная короткая ссылка.
     */
    public void shortenUrl(Scanner scanner) {
        String longUrl;
        while (true) {
            System.out.println("Введите исходный URL ('exit' - для перехода в меню):");
            longUrl = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(longUrl)) {
                System.out.println("Переход в меню.");
                return;
            }

            // Проверка доступности URL
            if (isUrlAccessible(longUrl)) {
                break;
            }
            System.out.println("URL недоступен или не существует. Попробуйте снова.");
        }

        String durationInput;
        long durationMs;
        do {
            System.out.println("Введите время действия ссылки (например, 5h 30m, 1h, 5m; 'exit' - для перехода в меню):");
            durationInput = scanner.nextLine();

            if ("exit".equalsIgnoreCase(durationInput)) {
                System.out.println("Переход в меню.");
                return;
            }

            durationMs = parseDuration(durationInput);  // Парсинг времени действия
        } while (durationMs <= 0);

        // Ограничение времени действия до максимального
        if (durationMs > MAX_EXPIRY_TIME_MS) {
            durationMs = MAX_EXPIRY_TIME_MS;
            System.out.println("Указанное время превышает максимальное значение – " + formatRemainingTime(System.currentTimeMillis() + MAX_EXPIRY_TIME_MS) + " (установлено автоматически).");
        }

        int visitLimit;
        while (true) {
            System.out.println("Введите число переходов по ссылке ('exit' - для перехода в меню):");
            String limitInput = scanner.nextLine();

            if ("exit".equalsIgnoreCase(durationInput)) {
                System.out.println("Переход в меню.");
                return;
            }

            try {
                visitLimit = Integer.parseInt(limitInput);

                // Проверка на лимит по умолчанию
                if (visitLimit < DEFAULT_LIMIT_REDIRECT) {
                    System.out.println("Указанный лимит переходов меньше значения по умолчанию – " + DEFAULT_LIMIT_REDIRECT + " (установлено автоматически).");
                    visitLimit = DEFAULT_LIMIT_REDIRECT;
                }

                break;
            } catch (NumberFormatException e) {
                System.out.println("Некорректный ввод. Введите положительное число.");
            }
        }

        // Создание короткой ссылки
        String uniqueId = UUID.randomUUID().toString().substring(0, 6);
        String shortUrl = BASE_URL + uniqueId;

        UserData currentUser = getCurrentUser();
        currentUser.getLinks().put(shortUrl, new LinkData(longUrl, System.currentTimeMillis() + durationMs, visitLimit));

        saveData();
        System.out.println("Короткая ссылка: " + shortUrl);
    }

    /**
     * Перенаправляет пользователя на исходный ресурс.
     */
    public void redirect(Scanner scanner) {
        removeExpiredLinks(); // Удаление устаревших ссылок

        while (true) {
            System.out.println("Введите короткую ссылку для перенаправления ('exit' - для перехода в меню):");
            String shortUrlInput = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(shortUrlInput)) {
                System.out.println("Переход в меню.");
                return;
            }

            // Локальная копия переменной для использования в лямбда-выражении
            final String shortUrl = shortUrlInput;

            // Проверка существования ссылки у любого пользователя
            LinkData link = users.values().stream()
                    .map(UserData::getLinks)
                    .map(links -> links.get(shortUrl))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            if (link != null) {
                if (link.getExpiryTime() <= System.currentTimeMillis()) {
                    System.out.println("Срок действия ссылки истёк. Исходная ссылка: " + link.getLongUrl());
                    return;
                }

                if (link.isLimitReached()) {
                    System.out.println("Лимит переходов по ссылке исчерпан. Исходная ссылка: " + link.getLongUrl());
                    return;
                }

                try {
                    Desktop.getDesktop().browse(new URI(link.getLongUrl()));
                    link.decrementVisitLimit();  // Увеличиваем счетчик переходов
                    saveData();
                    System.out.println("Перенаправление на: " + link.getLongUrl());
                } catch (Exception e) {
                    System.err.println("Ошибка при открытии URL: " + e.getMessage());
                }
                return;
            }

            System.out.println("Короткая ссылка не найдена. Попробуйте снова.");
        }
    }

    /**
     * Отображает все ссылки текущего пользователя с удалением просроченных ссылок.
     */
    public void showUserLinks() {
        removeExpiredLinks();   // Удаление устаревших ссылок

        UserData currentUser = getCurrentUser();
        Map<String, LinkData> links = currentUser.getLinks();

        if (links.isEmpty()) {
            System.out.println("У вас нет созданных ссылок.");
        } else {
            System.out.println("Ваши ссылки:");
            links.entrySet()
                    .stream()
                    .sorted(Comparator.comparing(entry -> entry.getValue().getLongUrl())) // Сортировка по исходным ссылкам
                    .forEach(entry -> {
                        String shortUrl = entry.getKey();
                        LinkData linkData = entry.getValue();
                        String remainingTime = formatRemainingTime(linkData.getExpiryTime());
                        System.out.println(shortUrl + " - " + linkData.getLongUrl() + " (" + remainingTime + ", " + linkData.getVisitLimit() + " redirects left)");
                    });
        }
    }

    /**
     * Отображает оставшееся время действия.
     */

    private String formatRemainingTime(long expiryTime) {
        long remainingMs = expiryTime - System.currentTimeMillis();
        if (remainingMs <= 0) return "истекло";

        long days = TimeUnit.MILLISECONDS.toDays(remainingMs);
        remainingMs -= TimeUnit.DAYS.toMillis(days);

        long hours = TimeUnit.MILLISECONDS.toHours(remainingMs);
        remainingMs -= TimeUnit.HOURS.toMillis(hours);

        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs);
        remainingMs -= TimeUnit.MINUTES.toMillis(minutes);

        long seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs);

        // Собираем строку времени в нужном формате
        StringBuilder timeRemaining = new StringBuilder();
        if (days > 0) timeRemaining.append(days).append("d ");
        if (hours > 0) timeRemaining.append(hours).append("h ");
        if (minutes > 0) timeRemaining.append(minutes).append("m ");
        if (seconds > 0) timeRemaining.append(seconds).append("s");

        return timeRemaining.toString().trim();
    }

    /**
     * Позволяет редактировать параметры короткой ссылки.
     */
    public void editLink(Scanner scanner) {
        removeExpiredLinks();   // Удаление устаревших ссылок
        UserData currentUser = getCurrentUser();

        String shortUrl;
        while (true) {
            System.out.println("Введите короткую ссылку для редактирования ('exit' - для перехода в меню):");
            shortUrl = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(shortUrl)) {
                System.out.println("Переход в меню.");
                return;
            }

            // Проверка существования ссылки у текущего пользователя
            if (currentUser.getLinks().containsKey(shortUrl)) {
                break;
            }
            System.out.println("Ссылка не существует или вы не являетесь её владельцем. Попробуйте снова.");
        }

        LinkData currentLink = currentUser.getLinks().get(shortUrl);
        if (currentLink == null) {
            System.out.println("Ссылка была удалена или устарела. Пожалуйста, выберите другую.");
            return;
        }

        String newLongUrl;
        while (true) {
            System.out.println("Текущая ссылка: " + currentLink.getLongUrl());
            System.out.println("Введите новый URL (нажмите Enter, чтобы оставить текущий или введите 'exit' - для перехода в меню):");
            newLongUrl = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(newLongUrl)) {
                System.out.println("Переход в меню.");
                return;
            }

            // Оставить текущий URL
            if (newLongUrl.isEmpty()) {
                newLongUrl = currentLink.getLongUrl();
                break;
            }

            // Проверка доступности нового URL
            if (isUrlAccessible(newLongUrl)) {
                break;
            }
            System.out.println("Новый URL недоступен или не существует. Попробуйте снова.");
        }

        String newDurationInput;
        long newExpiryTimeMs;
        while (true) {
            System.out.println("Текущее время действия ссылки: " + formatRemainingTime(currentLink.getExpiryTime()));
            System.out.println("Введите новое время действия (например, 1d 2h 30m; нажмите Enter, чтобы оставить текущее время; 'exit' - для перехода в меню):");
            newDurationInput = scanner.nextLine();
            if ("exit".equalsIgnoreCase(newDurationInput)) {
                System.out.println("Переход в меню.");
                return;
            }

            // Оставить текущее время действия
            if (newDurationInput.isEmpty()) {
                newExpiryTimeMs = currentLink.getExpiryTime();
                break;
            }

            // Парсинг времени действия
            newExpiryTimeMs = parseDuration(newDurationInput);
            if (newExpiryTimeMs > 0) {
                if (newExpiryTimeMs > MAX_EXPIRY_TIME_MS) {
                    System.out.println("Указанное время превышает максимальное значение – " + formatRemainingTime(System.currentTimeMillis() + MAX_EXPIRY_TIME_MS) + " (установлено автоматически).");
                    newExpiryTimeMs = MAX_EXPIRY_TIME_MS;
                }
                newExpiryTimeMs += System.currentTimeMillis(); // Установка нового времени истечения
                break;
            }
        }

        // Проверяем, не истекло ли новое время действия
        if (newExpiryTimeMs <= System.currentTimeMillis()) {
            System.out.println("Указанное время действия уже истекло. Ссылка не была обновлена.");
            return;
        }

        String newVisitLimitInput;
        int newVisitLimit;
        while (true) {
            System.out.println("Текущий лимит переходов: " + currentLink.getVisitLimit());
            System.out.println("Введите новый лимит переходов (нажмите Enter, чтобы оставить текущий лимит, 'exit' - для перехода в меню):");
            newVisitLimitInput = scanner.nextLine();
            if ("exit".equalsIgnoreCase(newVisitLimitInput)) {
                System.out.println("Переход в меню.");
                return;
            }

            // Оставить текущий лимит
            if (newVisitLimitInput.isEmpty()) {
                newVisitLimit = currentLink.getVisitLimit();
                break;
            }

            try {
                newVisitLimit = Integer.parseInt(newVisitLimitInput);

                // Проверка на лимит по умолчанию
                if (newVisitLimit < DEFAULT_LIMIT_REDIRECT) {
                    System.out.println("Указанный лимит переходов меньше значения по умолчанию – " + DEFAULT_LIMIT_REDIRECT + " (установлено автоматически).");
                    newVisitLimit = DEFAULT_LIMIT_REDIRECT;
                }

                break;
            } catch (NumberFormatException e) {
                System.out.println("Некорректное значение. Пожалуйста, введите целое число.");
            }
        }

        currentUser.getLinks().put(shortUrl, new LinkData(newLongUrl, newExpiryTimeMs, newVisitLimit));

        saveData();
        System.out.println("Ссылка успешно обновлена: " + shortUrl);
    }

    /**
     * Удаляет ссылку, если запрос отправил её создатель.
     */
    public void deleteUrl(Scanner scanner) {
        removeExpiredLinks();    // Удаление устаревших ссылок
        UserData currentUser = getCurrentUser();

        while (true) {
            System.out.println("Введите короткую ссылку для удаления ('exit' - для перехода в меню):");
            String shortUrl = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(shortUrl)) {
                System.out.println("Переход в меню.");
                return;
            }

            if (currentUser.getLinks().remove(shortUrl) != null) {
                System.out.println("Ссылка удалена.");
                saveData();
                return;
            } else {
                System.out.println("Ссылка не существует или вы не являетесь её владельцем.");
            }
        }
    }

    /**
     * Меняет текущего пользователя.
     */
    public void switchUser(Scanner scanner) {
        System.out.println("Вы собираетесь сменить пользователя. Текущая сессия будет завершена.");
        authenticate(scanner); // Аутентификация для нового пользователя
    }

    public static void main(String[] args) {
        shortLinks shortener = new shortLinks();
        Scanner scanner = new Scanner(System.in);

        // Аутентификация при запуске
        shortener.authenticate(scanner);

        while (true) {
            shortener.removeExpiredLinks();

            System.out.println("""
            ==========================================
            Выберите действие:
            1. Создать короткую ссылку
            2. Перейти по короткой ссылке
            3. Показать все созданные ссылки
            4. Редактировать параметры короткой ссылки
            5. Удалить короткую ссылку
            6. Сменить пользователя
            7. Выйти из программы
            ==========================================""");
            String input = scanner.nextLine();

            switch (input) {
                case "1" -> shortener.shortenUrl(scanner);
                case "2" -> shortener.redirect(scanner);
                case "3" -> shortener.showUserLinks();
                case "4" -> shortener.editLink(scanner);
                case "5" -> shortener.deleteUrl(scanner);
                case "6" -> shortener.switchUser(scanner);

                case "7" -> {
                    System.out.println("Выход из программы. Спасибо за использование!");
                    scanner.close();
                    return;
                }

                default -> System.out.println("Некорректный выбор. Попробуйте снова.");
            }
        }
    }
}