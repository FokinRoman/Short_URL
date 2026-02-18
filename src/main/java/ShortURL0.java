import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * Многопользовательский URL‑сокращатель с персистентностью (JSON) и базовой аутентификацией.
 */
class URLShortener {

    // Константы
    private static final int LINK_LIFETIME_HOURS = 24;
    private static final String BASE_URL = "clck.ru/";
    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final DataStorage storage = new DataStorage();
    private final ScheduledExecutorService cleanerService = Executors.newScheduledThreadPool(1);
    private int clicksLimit;
    private UUID userToken;

    public URLShortener() {
        cleanerService.scheduleAtFixedRate(this::cleanExpiredLinks, 5, 5, TimeUnit.MINUTES);
    }

    // Генерирует короткий код на основе UUID пользователя и случайного числа
    private String generateShortCode(UUID userUUID) {
        long randomNum = new Random().nextLong() & Long.MAX_VALUE;
        StringBuilder code = new StringBuilder();

        do {
            code.append(BASE62_CHARS.charAt((int) (randomNum % 62)));
            randomNum /= 62;
        } while (randomNum > 0);

        String uuidPart = userUUID.toString().substring(0, 4).toUpperCase();
        return (code.toString() + uuidPart).substring(0, 6);
    }

    // Создаёт короткую ссылку
    public String createShortLink(String longUrl,
                                  int clicksLimit,
                                  UUID userToken) {
        this.clicksLimit = clicksLimit;
        this.userToken = userToken;
        if (!isValidUrl(longUrl)) {
            throw new IllegalArgumentException("Некорректный URL");
        }

        String shortCode = generateShortCode(userToken);
        ShortLink link = new ShortLink(longUrl, shortCode, userToken, clicksLimit);
        storage.addLink(link);


        System.out.println("Создана короткая ссылка: " + BASE_URL + shortCode);
        System.out.println("Срок действия: до " + link.expiresAt);
        return BASE_URL + shortCode;
    }

    // Проверяет корректность URL
    private boolean isValidUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // Переходит по короткой ссылке
    public void redirect(String shortCode) {
        ShortLink link = storage.getLink(shortCode.replace(BASE_URL, ""));

        if (link == null) {
            System.out.println("Ошибка: ссылка не найдена");
            return;
        }

        if (isLinkExpired(link)) {
            System.out.println("Ошибка: срок действия ссылки истёк");
            sendExpirationNotification(link.creatorUUID, shortCode);
            return;
        }

        if (link.clicksRemaining <= 0) {
            System.out.println("Ошибка: лимит переходов исчерпан");
            sendLimitExceededNotification(link.creatorUUID, shortCode);
            return;
        }

        link.clicksRemaining--;

        try {
            Desktop.getDesktop().browse(new URI(link.originalUrl));
            System.out.println("Перенаправление на: " + link.originalUrl);
        } catch (IOException | URISyntaxException e) {
            System.out.println("Ошибка при открытии ссылки: " + e.getMessage());
        }
    }

    // Проверяет, истекла ли ссылка
    private boolean isLinkExpired(ShortLink link) {
        return LocalDateTime.now().isAfter(link.expiresAt);
    }

    // Очищает просроченные ссылки
    private void cleanExpiredLinks() {
        List<String> expiredCodes = new ArrayList<>();


        for (Map.Entry<String, ShortLink> entry : storage.links.entrySet()) {
            if (isLinkExpired(entry.getValue())) {
                expiredCodes.add(entry.getKey());
            }
        }

        for (String code : expiredCodes) {
            ShortLink expiredLink = storage.links.remove(code);
            if (expiredLink != null) {
                sendExpirationNotification(expiredLink.creatorUUID, code);
                System.out.println("Удалена просроченная ссылка: " + BASE_URL + code);
            }
        }
    }

    // Уведомление об истечении срока
    private void sendExpirationNotification(UUID userUUID, String shortCode) {
        System.out.println("[Уведомление] Ссылка " + BASE_URL + shortCode +
                " стала недоступна: истёк срок действия");
    }

    // Уведомление о достижении лимита
    private void sendLimitExceededNotification(UUID userUUID, String shortCode) {
        System.out.println("[Уведомление] Ссылка " + BASE_URL + shortCode +
                " стала недоступна: исчерпан лимит переходов");
    }

    // Возвращает список ссылок пользователя
    public List<ShortLink> getUserLinks(UUID userToken) {
        return storage.getUserLinks(userToken);
    }

    // Основной метод
    public static void main(String[] args) {
        URLShortener shortener = new URLShortener();
        Scanner scanner = new Scanner(System.in);
        UUID currentUserToken = null;

        System.out.println("=== URL Shortener (многопользовательский режим) ===");
        System.out.println("Доступные команды:");
        System.out.println("1. register <логин> <пароль>    — регистрация нового пользователя");
        System.out.println("2. login <логин> <пароль>       — вход в систему");
        System.out.println("3. create <Ваш URL> <лимит>     — создать короткую ссылку (только для авторизованных)");
        System.out.println("4. open <короткая ссылка>       — перейти по короткой ссылке");
        System.out.println("5. mylinks                      — показать мои ссылки");
        System.out.println("6. exit                         — выход");

        while (true) {
            System.out.print("\nВведите команду: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split(" ", 3);

            switch (parts[0].toLowerCase()) {
                case "register":
                    if (parts.length == 3) {
                        boolean success = shortener.storage.registerUser(parts[1], parts[2]);
                        if (success) {
                            System.out.println("Регистрация успешна. Теперь войдите в систему.");
                        } else {
                            System.out.println("Ошибка: пользователь с таким логином уже существует.");
                        }
                    } else {
                        System.out.println("Использование: register <логин> <пароль>");
                    }
                    break;

                case "login":
                    if (parts.length == 3) {
                        UUID token = shortener.storage.loginUser(parts[1], parts[2]);
                        if (token != null) {
                            currentUserToken = token;
                            System.out.println("Вход выполнен. Ваш токен: " + token);
                        } else {
                            System.out.println("Ошибка: неверный логин или пароль.");
                        }
                    } else {
                        System.out.println("Использование: login <логин> <пароль>");
                    }
                    break;

                case "create":
                    if (currentUserToken == null) {
                        System.out.println("Ошибка: войдите в систему, чтобы создавать ссылки.");
                        break;
                    }
                    if (parts.length == 3) {
                        try {
                            String longUrl = parts[1];
                            int limit = Integer.parseInt(parts[2]);
                            // Вызов метода создания ссылки
                            String shortLink = shortener.createShortLink(longUrl, limit, currentUserToken);
                            System.out.println("Короткая ссылка: " + shortLink);
                        } catch (NumberFormatException e) {
                            System.out.println("Ошибка: лимит должен быть числом.");
                        } catch (IllegalArgumentException e) {
                            System.out.println("Ошибка: " + e.getMessage());
                        }
                    } else {
                        System.out.println("Использование: create <URL> <лимит>");
                    }
                    break;

                case "open":
                    if (parts.length == 2) {
                        shortener.redirect(parts[1]);
                    } else {
                        System.out.println("Использование: open <короткая_ссылка>");
                    }
                    break;

                case "mylinks":
                    if (currentUserToken == null) {
                        System.out.println("Ошибка: войдите в систему, чтобы увидеть свои ссылки.");
                    } else {
                        List<ShortLink> links = shortener.getUserLinks(currentUserToken);
                        if (links.isEmpty()) {
                            System.out.println("У вас нет созданных ссылок.");
                        } else {
                            System.out.println("Ваши ссылки:");
                            for (ShortLink link : links) {
                                System.out.printf("- %s (осталось: %d, до: %s)%n",
                                        BASE_URL + link.shortCode,
                                        link.clicksRemaining,
                                        link.expiresAt);
                            }
                        }
                    }
                    break;

                case "exit":
                    System.out.println("Завершение работы...");
                    scanner.close();
                    shortener.cleanerService.shutdown();
                    return;

                default:
                    System.out.println("Неизвестная команда. Введите одну из: register, login, create, open, mylinks, exit");
            }
        }
    }

    // Вложенный класс: модель короткой ссылки
    static class ShortLink {
        @JsonProperty("originalUrl")
        public String originalUrl;

        @JsonProperty("shortCode")
        public String shortCode;

        @JsonProperty("creatorUUID")
        public UUID creatorUUID;

        @JsonProperty("clicksRemaining")
        public int clicksRemaining;

        @JsonProperty("createdAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        public LocalDateTime createdAt;

        @JsonProperty("expiresAt")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        public LocalDateTime expiresAt;

        public ShortLink() {}

        public ShortLink(String originalUrl, String shortCode, UUID creatorUUID, int clicksLimit) {
            this.originalUrl = originalUrl;
            this.shortCode = shortCode;
            this.creatorUUID = creatorUUID;
            this.clicksRemaining = clicksLimit;
            this.createdAt = LocalDateTime.now();
            this.expiresAt = this.createdAt.plusHours(LINK_LIFETIME_HOURS);
        }
    }

    // Вложенный класс: хранилище данных (JSON)
    static class DataStorage {
        private final ObjectMapper mapper = new ObjectMapper();
        private final File usersFile = new File("users.json");
        private final File linksFile = new File("links.json");

        private Map<UUID, User> users = new ConcurrentHashMap<>();
        private Map<String, ShortLink> links = new ConcurrentHashMap<>();

        public DataStorage() {
            // Добавляем поддержку java.time (LocalDateTime и др.)
            mapper.registerModule(new JavaTimeModule());
            // Отключаем запись временных зон (если не нужны)
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            loadData();
        }

        private void loadData() {
            try {
                if (usersFile.exists()) {
                    users = mapper.readValue(usersFile, mapper.getTypeFactory()
                            .constructMapType(Map.class, UUID.class, User.class));
                }
                if (linksFile.exists()) {
                    links = mapper.readValue(linksFile, mapper.getTypeFactory()
                            .constructMapType(Map.class, String.class, ShortLink.class));
                }
            } catch (IOException e) {
                System.err.println("Ошибка загрузки данных: " + e.getMessage());
            }
        }

        public void saveData() {
            try {
                mapper.writeValue(usersFile, users);
                mapper.writeValue(linksFile, links);
            } catch (IOException e) {
                System.err.println("Ошибка сохранения данных: " + e.getMessage());
            }
        }

        public boolean registerUser(String login, String password) {
            if (users.values().stream().anyMatch(u -> u.login.equals(login))) {
                return false;
            }
            User user = new User();
            user.login = login;
            user.passwordHash = hashPassword(password);
            user.token = UUID.randomUUID();
            users.put(user.token, user);
            saveData();
            return true;
        }

        public UUID loginUser(String login, String password) {
            for (User user : users.values()) {
                if (user.login.equals(login) && user.passwordHash.equals(hashPassword(password))) {
                    return user.token;
                }
            }
            return null;
        }

        public void addLink(ShortLink link) {
            links.put(link.shortCode, link);
            User user = users.get(link.creatorUUID);
            if (user != null) {
                user.shortLinks.add(link.shortCode);
            }
            saveData();
        }

        public ShortLink getLink(String shortCode) {
            return links.get(shortCode);
        }

        public List<ShortLink> getUserLinks(UUID userToken) {
            User user = users.get(userToken);
            if (user == null) return Collections.emptyList();
            return user.shortLinks.stream()
                    .map(links::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        private String hashPassword(String password) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(password.getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException("Ошибка хеширования", e);
            }
        }
    }

    // Вложенный класс: модель пользователя
    static class User {
        @JsonProperty("login")
        public String login;

        @JsonProperty("passwordHash")
        public String passwordHash;

        @JsonProperty("token")
        public UUID token;

        @JsonProperty("shortLinks")
        public List<String> shortLinks = new ArrayList<>();

        public User() {}
    }
}
