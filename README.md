# Keycloak Custom Password Policy Plugin

Custom password policy provider for Keycloak 26.5.0 that validates passwords based on user-specific forbidden words and complexity rules with multi-language support (English/Portuguese).

## Features

- **Minimum Length**: Configurable (default: 12 characters)
- **Forbidden Words**: Prevents use of:
  - Username
  - Any part of user's full name from LDAP `cn` attribute (first, middle, last names)
  - 4+ consecutive digits from username
  - Common Portuguese articles filtered out (da, das, de, do, dos)
  - Single-letter words excluded
- **Character Complexity**: Requires at least 3 out of 4 character groups:
  - Digits: `0-9`
  - Lowercase: `a-z`
  - Uppercase: `A-Z`
  - Symbols: `@!#$%&()=.:,;*<>`
- **Invalid Characters**: Blocks: `"'áàãâÁÀÃÂéèêÉÈÊíìîÍÌÎóòõôÓÒÕÔúùûÚÙÛçÇ€+-`
- **Multi-language**: Automatic English/Portuguese error messages based on user's locale
- **Detailed Error Messages**: Bullet-point format showing exactly what's missing

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Keycloak 26.5.0
- LDAP federation configured (for full name extraction)

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

## Configuration in Keycloak

### Step 1: Configure LDAP Mapper (Required for Full Name)

1. Go to **User Federation** → **ldap** (your LDAP provider)
2. Click on the **Mappers** tab
3. Click **Create**
4. Configure:
   - **Name**: `full-name`
   - **Mapper Type**: `user-attribute-ldap-mapper`
   - **LDAP Attribute**: `cn` (Common Name in LDAP)
   - **User Model Attribute**: `cn`
   - **Read Only**: ON
   - **Always Read Value From LDAP**: ON
5. Click **Save**
6. Go back to LDAP federation settings and click **Sync all users**

### Step 2: Enable Password Policy Validation in LDAP

1. Go to **User Federation** → **ldap**
2. Scroll down to find **Validate Password Policy**
3. Set it to **ON**
4. Save

### Step 3: Add Password Policy to Realm

1. Login to Keycloak Admin Console
2. Navigate to your realm
3. Go to **Realm Settings** → **Password Policy**
4. Click **Add policy**
5. Select **Custom Password Policy** from the dropdown
6. Set the value to **12** (minimum password length)
7. Click **Save**

## Error Messages

The plugin provides user-friendly error messages in bullet-point format:

**English Example:**
```
Password does not meet requirements:
• Password must be at least 12 characters long (current: 8)
• Password cworks with LDAP-federated users and extracts the full name from:
1. **Primary**: `cn` attribute (Common Name) - contains the complete name including middle names
2. **Fallback**: `displayName` attribute
3. **Fallback**: `firstName` + `lastName` attributes

**Important:** Make sure to configure the LDAP mapper (see Configuration section) to import the `cn` attribute, otherwise the plugin won't be able to block middle names.

## Security Considerations

- All name comparisons are **case-insensitive**
- The plugin blocks **any part** of the full name, not just first/last name
- Consecutive digits (4+) from username are also blocked
- Common Portuguese articles are filtered to avoid blocking valid passwords

**Portuguese Example:**
```
A senha não atende aos requisitos:
• A senha deve ter pelo menos 12 caracteres (atual: 8)
• A senha não pode conter 'joao' (parte do seu nome de usuário ou nome)
• A senha deve conter pelo menos 3 tipos de caracteres
• Encontrado: lowercase (a-z)
• Faltando: digits (0-9), uppercase (A-Z), symbols (@!#$%&()=.:,;*<>)
```

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

Th**Keycloak**: 26.5.0 (tested on 26.x series)
- **Java**: 17+
- **Maven**: 3.6+

## CVE Status

All critical and high-severity CVE vulnerabilities have been addressed by using Keycloak 26.5.0.

## Development

For developers who want to understand or modify this plugin, see [BUILDING.md](BUILDING.md) for:
- Complete project structure explanation
- Step-by-step guide to build from scratch
- Understanding Provider vs Factory pattern
- How Keycloak loads and invokes custom policiesannot contain the word [WORD]"
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
