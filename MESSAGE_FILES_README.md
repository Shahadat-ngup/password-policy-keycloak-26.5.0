# Internationalization (i18n) Implementation

## ✅ Complete Solution Implemented

The password policy now has **full internationalization support** with proper runtime message resolution!

### How It Works

1. **Message Properties Files** (`src/main/resources/`):
   - `messages_en.properties` - English messages
   - `messages_pt.properties` - Portuguese messages
   - Files are bundled inside the JAR automatically

2. **Runtime Message Loading**:
   - Java code detects user's locale automatically (from Keycloak context or HTTP headers)
   - Loads appropriate properties file at runtime
   - Substitutes parameters ({0}, {1}, etc.) dynamically
   - Caches loaded messages for performance

3. **No Theme Files Needed**:
   - Message files are embedded in the plugin JAR
   - No need to copy files to theme directories
   - Everything is self-contained

### Adding New Languages

To add French support:

1. **Create `src/main/resources/messages_fr.properties`**:
   ```properties
   invalidPasswordNull=Le mot de passe ne peut pas être nul
   invalidPasswordHistory=• Le mot de passe ne peut pas être réutilisé
   invalidPasswordMinLength=• Le mot de passe doit contenir au moins {0} caractères (actuel: {1})
   invalidPasswordContainsBadWord=• Le mot de passe ne peut pas contenir ''{0}''
   invalidPasswordComplexity=• Le mot de passe doit contenir au moins 3 types de caractères
   invalidPasswordComplexityFound=• Trouvé: {0}
   invalidPasswordComplexityMissing=• Manquant: {0}
   invalidPasswordInvalidChars=• Le mot de passe contient des caractères invalides: {0}
   invalidPasswordRequirements=Le mot de passe ne répond pas aux exigences:
   ```

2. **Rebuild and deploy**:
   ```bash
   mvn clean package
   scp target/custom-password-policy-1.0.0.jar shahadat@id-qa.ipb.pt:~/Keycloak-Docker/keycloak/providers/
   ```

3. **That's it!** No Java code changes needed. ✅

### Benefits

✅ **Language-agnostic Java code** - No hardcoded strings  
✅ **Easy to add languages** - Just create new `.properties` file  
✅ **Automatic locale detection** - Uses Keycloak's locale resolution  
✅ **Self-contained** - All messages bundled in JAR  
✅ **Performance optimized** - Messages cached after first load  
✅ **Maintainable** - Translators can work independently  

### Current Languages Supported

- 🇬🇧 English (`en`)
- 🇵🇹 Portuguese (`pt`)
- ➕ Any language you add (just create `messages_{code}.properties`)

### Message Keys Reference

| Key | Parameters | Purpose |
|-----|-----------|---------|
| `invalidPasswordNull` | None | Password is null |
| `invalidPasswordHistory` | None | Password reused from history |
| `invalidPasswordMinLength` | {0}=required, {1}=current | Too short |
| `invalidPasswordContainsBadWord` | {0}=forbidden word | Contains username/name |
| `invalidPasswordComplexity` | None | Needs more character types |
| `invalidPasswordComplexityFound` | {0}=found types | Lists what was found |
| `invalidPasswordComplexityMissing` | {0}=missing types | Lists what's missing |
| `invalidPasswordInvalidChars` | {0}=invalid chars | Contains forbidden characters |
| `invalidPasswordRequirements` | None | Error header |

### Deployment

The current build is ready to deploy:

```bash
# Deploy to production
scp target/custom-password-policy-1.0.0.jar shahadat@id-qa.ipb.pt:~/Keycloak-Docker/keycloak/providers/

# Restart Keycloak
ssh shahadat@id-qa.ipb.pt "cd ~/Keycloak-Docker && docker compose restart keycloak"
```

### Testing Different Languages

Users will automatically see messages in their preferred language based on:
1. Their Keycloak user profile locale setting
2. Their browser's `Accept-Language` header

To test:
- Change browser language settings
- Or set user locale in Keycloak admin console
- Error messages will automatically display in the correct language
