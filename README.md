# Keycloak Custom Password Policy Plugin

Custom password policy provider for Keycloak 26.5.0 that validates passwords based on user-specific forbidden words and complexity rules.

## Features

- **Minimum Length**: 12 characters minimum
- **Forbidden Words**: Prevents use of:
  - Username (UID)
  - Individual words from user's full name (firstName + lastName)
  - 4+ consecutive digits from username
  - Common words are filtered out (da, das, de, do, dos)
  - Single-letter words are excluded
- **Character Complexity**: Requires at least 3 out of 4 character groups:
  - Digits: `0-9`
  - Lowercase: `a-z`
  - Uppercase: `A-Z`
  - Symbols: `@!#$%&()=.:,;*<>`
- **Invalid Characters**: Blocks: `"'áàãâÁÀÃÂéèêÉÈÊíìîÍÌÎóòõôÓÒÕÔúùûÚÙÛçÇ€+-`

## Build

```bash
mvn clean package
```

This will create `target/custom-password-policy-1.0.0.jar`

## Installation

### Method 1: Deployment Directory (Recommended for Keycloak 26.x)

1. Copy the JAR file to Keycloak providers directory:
```bash
cp target/custom-password-policy-1.0.0.jar /opt/keycloak/providers/
```

2. Rebuild Keycloak (if using optimized mode):
```bash
/opt/keycloak/bin/kc.sh build
```

3. Restart Keycloak:
```bash
/opt/keycloak/bin/kc.sh start
```

### Method 2: Docker

If using Docker, mount the JAR file:

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.0.5
    volumes:
      - ./custom-password-policy-1.0.0.jar:/opt/keycloak/providers/custom-password-policy-1.0.0.jar
    command: start-dev
```

## Configuration

1. Login to Keycloak Admin Console
2. Navigate to your realm
3. Go to **Realm Settings** → **Security Defenses** → **Password Policy**
4. Click **Add policy**
5. Select **Custom Password Policy** from the dropdown
6. Leave the value as `true` (enabled)
7. Save

## How It Works

### Name Processing Example

For user:
- Username: `hossain`
- Full Name: `Shahadat Hossain Dewan`

The plugin will extract forbidden words:
1. `hossain` (username)
2. `Shahadat` (from first name)
3. `Hossain` (from last name)
4. `Dewan` (from last name)

Notes:
- Common words like "da", "das", "de", "do", "dos" are filtered out
- Single-letter words are removed
- Case-insensitive matching

### Password Validation

Example invalid passwords:
- `MyPass123!` ❌ Too short (< 12 chars)
- `shahadat123456` ❌ Contains forbidden word "shahadat"
- `ValidPassword!` ❌ Missing digits (only 2 groups)
- `Valid+Pass123` ❌ Contains invalid character `+`

Example valid password:
- `MyStr0ng!Pass` ✅ 13 chars, no forbidden words, 4 groups, no invalid chars

## LDAP Integration

This plugin automatically works with LDAP-federated users. It extracts:
- `firstName` attribute
- `lastName` attribute
- `cn` (Common Name) attribute as fallback
- `username`

## Testing

To test the policy:

1. Create a test user with a full name
2. Try to set passwords that violate each rule
3. Verify error messages appear correctly

## Troubleshooting

### Plugin Not Appearing in Admin Console

1. Check Keycloak logs:
```bash
tail -f /opt/keycloak/data/log/keycloak.log
```

2. Verify JAR is in the correct location:
```bash
ls -la /opt/keycloak/providers/
```

3. Ensure Keycloak was rebuilt after adding the JAR

### Error Messages Not Clear

The plugin provides specific error messages:
- "Password must be at least 12 characters long (Found: X)"
- "Password cannot contain the word [WORD]"
- "Password must contain at least 3 of 4 character groups (Found: X)"
- "Password contains invalid characters: X"

## Version Compatibility

- Keycloak: 26.0.5 (26.x series)
- Java: 17+
- Maven: 3.6+

## License

This is a custom implementation. Modify as needed for your organization.

## Support

For issues or questions, refer to the Keycloak SPI documentation:
https://www.keycloak.org/docs/latest/server_development/
