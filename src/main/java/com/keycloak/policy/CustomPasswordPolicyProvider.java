package com.keycloak.policy;

import org.keycloak.models.KeycloakContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PolicyError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom password policy provider that validates passwords based on:
 * - Minimum length (12 characters)
 * - Forbidden words extracted from user's full name and username
 * - Character complexity (3 out of 4 groups: digits, lowercase, uppercase, symbols)
 * - Invalid character restrictions
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

    public CustomPasswordPolicyProvider(KeycloakContext context) {
        this.context = context;
    }

    @Override
    public PolicyError validate(String username, String password) {
        return validate(null, null, password);
    }

    @Override
    public PolicyError validate(RealmModel realm, UserModel user, String password) {
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
        return checkPassword(password, badWords);
    }

    /**
     * Extract full name from user attributes
     */
    private String extractFullName(UserModel user) {
        StringBuilder fullName = new StringBuilder();
        
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
        
        // Fallback to check for a "fullName" or "cn" attribute (common in LDAP)
        if (fullName.length() == 0) {
            String cnAttribute = user.getFirstAttribute("cn");
            if (cnAttribute != null && !cnAttribute.isEmpty()) {
                fullName.append(cnAttribute);
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
    private PolicyError checkPassword(String password, List<String> badWords) {
        if (password == null) {
            return new PolicyError("Password cannot be null");
        }
        
        int passwordLength = password.length();
        
        // Check minimum length
        if (passwordLength < MIN_LENGTH) {
            return new PolicyError("custom-password-policy", 
                String.format("Password must be at least %d characters long (Found: %d)", MIN_LENGTH, passwordLength));
        }
        
        // Check for bad words
        for (String badWord : badWords) {
            if (badWord != null && !badWord.isEmpty()) {
                if (password.toLowerCase().contains(badWord.toLowerCase())) {
                    return new PolicyError("custom-password-policy", 
                        String.format("Password cannot contain the word [%s]", badWord.toUpperCase()));
                }
            }
        }
        
        // Check character complexity (3 out of 4 groups)
        int groupsFound = 0;
        StringBuilder foundGroups = new StringBuilder();
        
        if (Pattern.compile("[" + Pattern.quote(DIGITS) + "]").matcher(password).find()) {
            foundGroups.append("0-9");
            groupsFound++;
        }
        if (Pattern.compile("[" + LOWERCASE + "]").matcher(password).find()) {
            foundGroups.append("a-z");
            groupsFound++;
        }
        if (Pattern.compile("[" + UPPERCASE + "]").matcher(password).find()) {
            foundGroups.append("A-Z");
            groupsFound++;
        }
        if (Pattern.compile("[" + Pattern.quote(SYMBOLS) + "]").matcher(password).find()) {
            foundGroups.append("#");
            groupsFound++;
        }
        
        if (groupsFound < 3) {
            return new PolicyError("custom-password-policy", 
                String.format("Password must contain at least 3 of 4 character groups (Found: %s)", foundGroups.toString()));
        }
        
        // Check for invalid characters
        Pattern invalidPattern = Pattern.compile("[" + Pattern.quote(INVALID_CHARS) + "]");
        Matcher invalidMatcher = invalidPattern.matcher(password);
        if (invalidMatcher.find()) {
            List<String> invalidFound = new ArrayList<>();
            do {
                invalidFound.add(invalidMatcher.group());
            } while (invalidMatcher.find());
            
            return new PolicyError("custom-password-policy", 
                String.format("Password contains invalid characters: %s", String.join("", invalidFound)));
        }
        
        return null;
    }

    @Override
    public Object parseConfig(String value) {
        return value;
    }

    @Override
    public void close() {
        // No resources to close
    }
}
