package com.keycloak.policy;

import org.keycloak.models.KeycloakContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PolicyError;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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

    public CustomPasswordPolicyProvider(KeycloakContext context) {
        this.context = context;
        this.configuredMinLength = null;
    }
    
    private boolean isPortuguese(UserModel user) {
        try {
            if (context != null && user != null) {
                String locale = context.resolveLocale(user).getLanguage();
                return "pt".equals(locale);
            }
            // Fallback to checking HTTP request locale
            if (context != null && context.getRequestHeaders() != null) {
                String acceptLanguage = context.getRequestHeaders().getHeaderString("Accept-Language");
                if (acceptLanguage != null && acceptLanguage.toLowerCase().contains("pt")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // If locale detection fails, default to English
        }
        return false;
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
     */
    private PolicyError checkPassword(String password, List<String> badWords, UserModel user) {
        if (password == null) {
            boolean isPt = isPortuguese(user);
            return new PolicyError(isPt ? "A senha não pode ser nula" : "Password cannot be null");
        }
        
        boolean isPt = isPortuguese(user);
        int passwordLength = password.length();
        int requiredMinLength = (configuredMinLength != null && configuredMinLength > 0) ? configuredMinLength : MIN_LENGTH;
        
        List<String> errors = new ArrayList<>();
        
        // Check password history
        if (user != null && user.getUsername() != null) {
            if (checkPasswordHistory(user.getUsername(), password)) {
                errors.add(String.format("• %s", 
                    isPt ? "A senha não pode ser reutilizada de históricos anteriores"
                         : "Password cannot be reused from previous history"));
            }
        }
        
        // Check minimum length
        if (passwordLength < requiredMinLength) {
            errors.add(String.format("• %s", 
                isPt ? String.format("A senha deve ter pelo menos %d caracteres (atual: %d)", requiredMinLength, passwordLength)
                     : String.format("Password must be at least %d characters long (current: %d)", requiredMinLength, passwordLength)));
        }
        
        // Check for bad words
        for (String badWord : badWords) {
            if (badWord != null && !badWord.isEmpty()) {
                if (password.toLowerCase().contains(badWord.toLowerCase())) {
                    errors.add(String.format("• %s", 
                        isPt ? String.format("A senha não pode conter '%s' (parte do seu nome de usuário ou nome)", badWord)
                             : String.format("Password cannot contain '%s' (part of your username or name)", badWord)));
                    break; // Only report the first match to avoid clutter
                }
            }
        }
        
        // Check character complexity (3 out of 4 groups)
        int groupsFound = 0;
        List<String> foundGroups = new ArrayList<>();
        List<String> missingGroups = new ArrayList<>();
        
        if (Pattern.compile("[" + Pattern.quote(DIGITS) + "]").matcher(password).find()) {
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
            if (isPt) {
                errors.add("• A senha deve conter pelo menos 3 tipos de caracteres");
                errors.add(String.format("• Encontrado: %s", 
                    foundGroups.isEmpty() ? "nenhum" : String.join(", ", foundGroups)));
                errors.add(String.format("• Faltando: %s", String.join(", ", missingGroups)));
            } else {
                errors.add("• Password must contain at least 3 character types");
                errors.add(String.format("• Found: %s", 
                    foundGroups.isEmpty() ? "none" : String.join(", ", foundGroups)));
                errors.add(String.format("• Missing: %s", String.join(", ", missingGroups)));
            }
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
            
            errors.add(String.format("• %s", 
                isPt ? String.format("A senha contém caracteres inválidos: %s (evite aspas, letras acentuadas, €+-)", String.join(", ", invalidFound))
                     : String.format("Password contains invalid characters: %s (avoid quotes, accented letters, €+-)", String.join(", ", invalidFound))));
        }
        
        // Return all errors as bullet points
        if (!errors.isEmpty()) {
            String header = isPt ? "A senha não atende aos requisitos:" : "Password does not meet requirements:";
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
