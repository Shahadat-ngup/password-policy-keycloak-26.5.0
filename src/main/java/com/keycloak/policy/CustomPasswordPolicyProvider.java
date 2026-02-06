package com.keycloak.policy;

import org.keycloak.models.KeycloakContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PolicyError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom password policy provider that validates passwords based on:
 * - Minimum length (12 characters)
 * - Forbidden words extracted from user's full name and username
 * - Character complexity (3 out of 4 groups: digits, lowercase, uppercase, symbols)
 * - Invalid character restrictions
 * - Password history (cannot reuse previous passwords)
 */
public class CustomPasswordPolicyProvider implements PasswordPolicyProvider {

    private static final int MIN_LENGTH = 12;
    private static final String DIGITS = "0-9";
    private static final String LOWERCASE = "a-z";
    private static final String UPPERCASE = "A-Z";
    private static final String SYMBOLS = "@!#$%&()=.:,;*<>";
    private static final String INVALID_CHARS = "\"'áàãâÁÀÃÂéèêÉÈÊíìîÍÌÎóòõôÓÒÕÔúùûÚÙÛçÇ€+-";
    
    private static final List<String> FILTER_WORDS = Arrays.asList("da", "das", "de", "do", "dos");
    
    private final KeycloakContext context;
    private Integer configuredMinLength;
    private static final Map<String, Properties> messageCache = new HashMap<>();

    public CustomPasswordPolicyProvider(KeycloakContext context) {
        this.context = context;
        this.configuredMinLength = null;
    }
    
    /**
     * Get localized message from properties files
     */
    private String getMessage(String key, UserModel user, Object... params) {
        String locale = getLocale(user);
        Properties messages = loadMessages(locale);
        
        String message = messages.getProperty(key, key);
        
        // Replace parameters {0}, {1}, etc.
        if (params != null && params.length > 0) {
            for (int i = 0; i < params.length; i++) {
                message = message.replace("{" + i + "}", String.valueOf(params[i]));
            }
        }
        
        return message;
    }
    
    /**
     * Detect user's locale
     */
    private String getLocale(UserModel user) {
        try {
            if (context != null && user != null) {
                String locale = context.resolveLocale(user).getLanguage();
                return locale != null ? locale : "en";
            }
            // Fallback to checking HTTP request locale
            if (context != null && context.getRequestHeaders() != null) {
                String acceptLanguage = context.getRequestHeaders().getHeaderString("Accept-Language");
                if (acceptLanguage != null && acceptLanguage.toLowerCase().contains("pt")) {
                    return "pt";
                }
            }
        } catch (Exception e) {
            // If locale detection fails, default to English
        }
        return "en";
    }
    
    /**
     * Load message properties file for given locale
     * Priority: 1) External directory (/opt/keycloak/messages/), 2) JAR (classpath)
     */
    private Properties loadMessages(String locale) {
        // Check cache first
        if (messageCache.containsKey(locale)) {
            return messageCache.get(locale);
        }
        
        Properties props = new Properties();
        String filename = "messages_" + locale + ".properties";
        
        // Try loading from external directory first
        try (InputStream is = new java.io.FileInputStream("/opt/keycloak/messages/" + filename)) {
            props.load(is);
            messageCache.put(locale, props);
            System.out.println("[CUSTOM-PASSWORD-POLICY] Loaded messages from external: " + filename);
            return props;
        } catch (IOException e) {
            // File not found externally, try JAR
        }
        
        // Fallback to JAR (classpath)
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (is != null) {
                props.load(is);
                messageCache.put(locale, props);
                System.out.println("[CUSTOM-PASSWORD-POLICY] Loaded messages from JAR: " + filename);
            } else {
                // Fallback to English if locale file not found
                if (!"en".equals(locale)) {
                    return loadMessages("en");
                }
            }
        } catch (IOException e) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Error loading messages: " + e.getMessage());
        }
        
        return props;
    }

    public void setConfiguredMinLength(Integer minLength) {
        this.configuredMinLength = minLength;
    }

    @Override
    public PolicyError validate(String username, String password) {
        System.out.println("[CUSTOM-PASSWORD-POLICY] validate(username, password) called - username: " + username);
        return validate(null, null, password);
    }

    @Override
    public PolicyError validate(RealmModel realm, UserModel user, String password) {
        System.out.println("[CUSTOM-PASSWORD-POLICY] validate(realm, user, password) called");
        // Extract bad words from user information
        List<String> badWords = new ArrayList<>();
        
        if (user != null) {
            String username = user.getUsername();
            if (username != null && !username.isEmpty()) {
                badWords.add(username);
            }
            
            // Extract full name
            String fullName = extractFullName(user);
            if (fullName != null && !fullName.isEmpty()) {
                List<String> nameWords = processName(fullName);
                badWords.addAll(nameWords);
            }
            
            // Extract 4+ consecutive digits from username
            if (username != null) {
                Pattern digitPattern = Pattern.compile("\\d{4,}");
                Matcher matcher = digitPattern.matcher(username);
                if (matcher.find()) {
                    badWords.add(0, matcher.group(0));
                }
            }
        }
        
        // Validate password
        return checkPassword(password, badWords, user);
    }

    /**
     * Hash password using SHA-512 and Base64 encoding
     * Matches PHP: bin2hex(base64_encode(hash('sha512', $password, true)))
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            byte[] base64Encoded = Base64.getEncoder().encode(hash);
            return bytesToHex(base64Encoded);
        } catch (Exception e) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Error hashing password: " + e.getMessage());
            return null;
        }
    }

    /**
     * Convert bytes to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Check if password exists in password history
     * Returns true if password was used before
     */
    private boolean checkPasswordHistory(String username, String password) {
        String dbHost = System.getenv("PASSWORD_HISTORY_DB_HOST");
        String dbPort = System.getenv("PASSWORD_HISTORY_DB_PORT");
        String dbName = System.getenv("PASSWORD_HISTORY_DB_NAME");
        String dbUser = System.getenv("PASSWORD_HISTORY_DB_USER");
        String dbPass = System.getenv("PASSWORD_HISTORY_DB_PASSWORD");

        // If configuration is missing, skip history check
        if (dbHost == null || dbName == null || dbUser == null || dbPass == null) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Password history database not configured, skipping check");
            return false;
        }

        if (dbPort == null) {
            dbPort = "3306";
        }

        String hash = hashPassword(password);
        if (hash == null) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Failed to hash password");
            return false;
        }

        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?connectTimeout=5000", dbHost, dbPort, dbName);
        String sql = "SELECT COUNT(*) AS count FROM bdalunos.hashes WHERE uid = ? AND hash = ?";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, hash);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt("count");
                    return count > 0;
                }
            }
        } catch (Exception e) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Error checking password history: " + e.getMessage());
            // If database is unreachable, deny password change for security
            return true; // Treat as "password already used" to block change
        }

        return false;
    }

    /**
     * Store new password hash in history
     * Called after password validation succeeds
     */
    private void storePasswordHistory(String username, String password) {
        String dbHost = System.getenv("PASSWORD_HISTORY_DB_HOST");
        String dbPort = System.getenv("PASSWORD_HISTORY_DB_PORT");
        String dbName = System.getenv("PASSWORD_HISTORY_DB_NAME");
        String dbUser = System.getenv("PASSWORD_HISTORY_DB_USER");
        String dbPass = System.getenv("PASSWORD_HISTORY_DB_PASSWORD");

        if (dbHost == null || dbName == null || dbUser == null || dbPass == null) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Password history database not configured, skipping storage");
            return;
        }

        if (dbPort == null) {
            dbPort = "3306";
        }

        String hash = hashPassword(password);
        if (hash == null) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Failed to hash password for storage");
            return;
        }

        // Get client IP
        String clientIp = "unknown";
        if (context != null && context.getConnection() != null) {
            clientIp = context.getConnection().getRemoteAddr();
        }

        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?connectTimeout=5000", dbHost, dbPort, dbName);
        String sql = "INSERT INTO bdalunos.hashes (uid, hash, date, ip) VALUES (?, ?, NOW(), ?)";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, hash);
            stmt.setString(3, clientIp);
            
            stmt.executeUpdate();
            System.out.println("[CUSTOM-PASSWORD-POLICY] Password history stored for user: " + username);
        } catch (Exception e) {
            System.err.println("[CUSTOM-PASSWORD-POLICY] Error storing password history: " + e.getMessage());
        }
    }

    /**
     * Extract full name from user attributes
     * Tries multiple attributes to get the complete name
     */
    private String extractFullName(UserModel user) {
        StringBuilder fullName = new StringBuilder();
        
        // Try to get full name from common LDAP attributes first
        String cnAttribute = user.getFirstAttribute("cn");
        String displayName = user.getFirstAttribute("displayName");
        
        if (cnAttribute != null && !cnAttribute.isEmpty()) {
            fullName.append(cnAttribute);
        } else if (displayName != null && !displayName.isEmpty()) {
            fullName.append(displayName);
        } else {
            // Fallback to firstName + lastName
            String firstName = user.getFirstName();
            String lastName = user.getLastName();
            
            if (firstName != null && !firstName.isEmpty()) {
                fullName.append(firstName);
            }
            
            if (lastName != null && !lastName.isEmpty()) {
                if (fullName.length() > 0) {
                    fullName.append(" ");
                }
                fullName.append(lastName);
            }
        }
        
        return fullName.toString();
    }

    /**
     * Process name to extract individual words that should be forbidden
     * Based on the PHP logic provided
     */
    private List<String> processName(String name) {
        List<String> words = new ArrayList<>();
        
        if (name == null || name.isEmpty()) {
            return words;
        }
        
        // Remove non-alphabetic characters (except spaces)
        String cleanName = name.replaceAll("[^a-zA-Z\\s]", "");
        
        // Remove filter words (da, das, de, do, dos)
        for (String filterWord : FILTER_WORDS) {
            cleanName = cleanName.replaceAll("(?i)\\b" + filterWord + "\\b", "");
        }
        
        // Remove single letter words
        cleanName = cleanName.replaceAll("\\b\\w\\b\\s?", "");
        
        // Normalize whitespace
        cleanName = cleanName.trim().replaceAll("\\s+", " ");
        
        // Split into words
        if (!cleanName.isEmpty()) {
            String[] nameArray = cleanName.split("\\s+");
            for (String word : nameArray) {
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        
        return words;
    }

    /**
     * Check password against all validation rules
     * Uses localized messages from properties files
     */
    private PolicyError checkPassword(String password, List<String> badWords, UserModel user) {
        if (password == null) {
            return new PolicyError(getMessage("invalidPasswordNull", user));
        }
        
        int passwordLength = password.length();
        int requiredMinLength = (configuredMinLength != null && configuredMinLength > 0) ? configuredMinLength : MIN_LENGTH;
        
        List<String> errors = new ArrayList<>();
        
        // Check password history
        if (user != null && user.getUsername() != null) {
            if (checkPasswordHistory(user.getUsername(), password)) {
                errors.add(getMessage("invalidPasswordHistory", user));
            }
        }
        
        // Check minimum length
        if (passwordLength < requiredMinLength) {
            errors.add(getMessage("invalidPasswordMinLength", user, requiredMinLength, passwordLength));
        }
        
        // Check for bad words
        for (String badWord : badWords) {
            if (badWord != null && !badWord.isEmpty()) {
                if (password.toLowerCase().contains(badWord.toLowerCase())) {
                    errors.add(getMessage("invalidPasswordContainsBadWord", user, badWord));
                    break; // Only report the first match to avoid clutter
                }
            }
        }
        
        // Check character complexity (3 out of 4 groups)
        int groupsFound = 0;
        List<String> foundGroups = new ArrayList<>();
        List<String> missingGroups = new ArrayList<>();
        
        if (Pattern.compile("[" + DIGITS + "]").matcher(password).find()) {
            foundGroups.add("digits (0-9)");
            groupsFound++;
        } else {
            missingGroups.add("digits (0-9)");
        }
        
        if (Pattern.compile("[" + LOWERCASE + "]").matcher(password).find()) {
            foundGroups.add("lowercase (a-z)");
            groupsFound++;
        } else {
            missingGroups.add("lowercase (a-z)");
        }
        
        if (Pattern.compile("[" + UPPERCASE + "]").matcher(password).find()) {
            foundGroups.add("uppercase (A-Z)");
            groupsFound++;
        } else {
            missingGroups.add("uppercase (A-Z)");
        }
        
        if (Pattern.compile("[" + Pattern.quote(SYMBOLS) + "]").matcher(password).find()) {
            foundGroups.add("symbols (@!#$%&()=.:,;*<>)");
            groupsFound++;
        } else {
            missingGroups.add("symbols (@!#$%&()=.:,;*<>)");
        }
        
        if (groupsFound < 3) {
            String found = foundGroups.isEmpty() ? "none" : String.join(", ", foundGroups);
            String missing = String.join(", ", missingGroups);
            errors.add(getMessage("invalidPasswordComplexity", user));
            errors.add(getMessage("invalidPasswordComplexityFound", user, found));
            errors.add(getMessage("invalidPasswordComplexityMissing", user, missing));
        }
        
        // Check for invalid characters
        Pattern invalidPattern = Pattern.compile("[" + Pattern.quote(INVALID_CHARS) + "]");
        Matcher invalidMatcher = invalidPattern.matcher(password);
        if (invalidMatcher.find()) {
            List<String> invalidFound = new ArrayList<>();
            do {
                String found = invalidMatcher.group();
                if (!invalidFound.contains(found)) {
                    invalidFound.add(found);
                }
            } while (invalidMatcher.find());
            
            errors.add(getMessage("invalidPasswordInvalidChars", user, String.join(", ", invalidFound)));
        }
        
        // Return all errors
        if (!errors.isEmpty()) {
            String header = getMessage("invalidPasswordRequirements", user);
            return new PolicyError(header + "<br/>" + String.join("<br/>", errors));
        }
        
        // All validations passed - store password in history
        if (user != null && user.getUsername() != null && password != null) {
            storePasswordHistory(user.getUsername(), password);
        }
        
        return null;
    }

    @Override
    public Object parseConfig(String value) {
        return value != null && !value.isEmpty() ? Integer.valueOf(value) : MIN_LENGTH;
    }

    @Override
    public void close() {
        // No resources to close
    }
}
