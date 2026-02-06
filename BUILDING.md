# Building a Keycloak Password Policy Plugin From Scratch

This guide explains how to build a custom Keycloak password policy plugin from the ground up, helping you understand every component and how they work together.

## Table of Contents

1. [Understanding Keycloak SPI Architecture](#understanding-keycloak-spi-architecture)
2. [Project Structure](#project-structure)
3. [Step-by-Step Build Process](#step-by-step-build-process)
4. [Understanding Each Component](#understanding-each-component)
5. [How Components Connect](#how-components-connect)
6. [Testing Your Plugin](#testing-your-plugin)

---

## Understanding Keycloak SPI Architecture

### What is an SPI?

**SPI** = **Service Provider Interface**

Keycloak uses SPIs to allow developers to extend and customize its functionality. Think of it like a plug-in system where Keycloak defines the "socket" (interface) and you provide the "plug" (implementation).

### The Three Key Components

For a password policy plugin, you need three components:

1. **Provider** (The Worker)
   - Does the actual work (validates passwords)
   - Contains your business logic
   - Lives only during the validation request
   - Implements: `PasswordPolicyProvider`

2. **Factory** (The Manager)
   - Creates instances of the Provider
   - Registers your plugin with Keycloak
   - Defines configuration options (name, type, default values)
   - Lives for the entire Keycloak lifecycle
   - Implements: `PasswordPolicyProviderFactory`

3. **Service Descriptor** (The Registry)
   - A text file that tells Keycloak your plugin exists
   - Located in: `META-INF/services/`
   - Contains the fully-qualified class name of your Factory

### The Factory-Provider Pattern

```
Keycloak Startup
      ↓
Reads Service Descriptor → Finds Your Factory Class
      ↓
Initializes Factory → Calls init() and postInit()
      ↓
Factory Stays in Memory
      ↓
When User Changes Password
      ↓
Keycloak Calls Factory.create() → Returns New Provider Instance
      ↓
Provider.validate() → Checks Password → Returns Error or null
      ↓
Provider.close() → Provider Destroyed
```

**Why this pattern?**
- **Factory**: Expensive to create, lives long, shared across all requests
- **Provider**: Cheap to create, short-lived, one per validation request
- **Result**: Better performance and resource management

---

## Project Structure

### Mandatory Folder Structure

```
password-policy/                          # Root project folder
├── pom.xml                               # Maven build configuration
├── README.md                             # User documentation
├── BUILDING.md                           # This file
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── keycloak/
        │           └── policy/
        │               ├── CustomPasswordPolicyProvider.java        # The Worker
        │               └── CustomPasswordPolicyProviderFactory.java # The Manager
        └── resources/
            └── META-INF/
                └── services/
                    └── org.keycloak.policy.PasswordPolicyProviderFactory  # The Registry
```

### Why This Structure?

- **`src/main/java/`**: Java source code location (Maven convention)
- **Package name** (`com.keycloak.policy`): Organizes your classes, prevents naming conflicts
- **`src/main/resources/`**: Non-code files that go into the JAR
- **`META-INF/services/`**: Java SPI standard location for service descriptors

---

## Step-by-Step Build Process

### Step 1: Create Project Structure

```bash
# Create root directory
mkdir password-policy
cd password-policy

# Create folder structure
mkdir -p src/main/java/com/keycloak/policy
mkdir -p src/main/resources/META-INF/services
```

### Step 2: Create pom.xml

The `pom.xml` is Maven's configuration file. It defines:
- Project metadata (name, version, groupId, artifactId)
- Dependencies (libraries your code needs)
- Build settings (Java version, compiler options)

**Key sections explained:**

```xml
<groupId>com.keycloak.policy</groupId>
```
- Groups related projects together
- Often matches your package name
- Think of it as your "organization ID"

```xml
<artifactId>custom-password-policy</artifactId>
```
- The name of THIS specific project
- Becomes part of the JAR filename
- Should be descriptive and unique

```xml
<version>1.0.0</version>
```
- Your plugin version
- Follow semantic versioning: MAJOR.MINOR.PATCH

```xml
<packaging>jar</packaging>
```
- Tells Maven to build a JAR (Java Archive) file
- JAR = zipped collection of .class files and resources

```xml
<keycloak.version>26.5.0</keycloak.version>
```
- Sets Keycloak version for all dependencies
- Must match your Keycloak server version

```xml
<scope>provided</scope>
```
- Means "needed to compile, but Keycloak already has it at runtime"
- Prevents including duplicate libraries in your JAR
- Keeps JAR size small

```xml
<release>17</release>
```
- Compiles for Java 17
- Ensures compatibility with Keycloak's Java version

**Create the file:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Project Identity -->
    <groupId>com.keycloak.policy</groupId>
    <artifactId>custom-password-policy</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <!-- Project Info -->
    <name>Keycloak Custom Password Policy</name>
    <description>Custom password policy with name-based validation</description>

    <!-- Build Settings -->
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <keycloak.version>26.5.0</keycloak.version>
    </properties>

    <!-- Dependencies: Libraries We Need -->
    <dependencies>
        <!-- Keycloak Server SPI: Interface definitions -->
        <dependency>
            <groupId>org.keycloak</groupId>
            <artifactId>keycloak-server-spi</artifactId>
            <version>${keycloak.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Keycloak Server SPI Private: Internal interfaces -->
        <dependency>
            <groupId>org.keycloak</groupId>
            <artifactId>keycloak-server-spi-private</artifactId>
            <version>${keycloak.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Keycloak Core: Common classes -->
        <dependency>
            <groupId>org.keycloak</groupId>
            <artifactId>keycloak-core</artifactId>
            <version>${keycloak.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Keycloak Services: Service classes -->
        <dependency>
            <groupId>org.keycloak</groupId>
            <artifactId>keycloak-services</artifactId>
            <version>${keycloak.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <!-- Build Configuration -->
    <build>
        <plugins>
            <!-- Java Compiler Plugin -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <release>17</release>
                </configuration>
            </plugin>
            
            <!-- JAR Packaging Plugin -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Implementation-Title>${project.name}</Implementation-Title>
                            <Implementation-Version>${project.version}</Implementation-Version>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Step 3: Create the Factory Class

File: `src/main/java/com/keycloak/policy/CustomPasswordPolicyProviderFactory.java`

**Every line explained:**

```java
package com.keycloak.policy;
```
- Declares which package this class belongs to
- Must match the folder structure

```java
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PasswordPolicyProviderFactory;
```
- Imports classes from Keycloak's libraries
- These are interfaces and classes we need to use
- `Config`: Configuration scope
- `KeycloakSession`: Current request session
- `KeycloakSessionFactory`: Factory for sessions
- `PasswordPolicyProvider`: Interface we implement
- `PasswordPolicyProviderFactory`: Interface we implement

```java
public class CustomPasswordPolicyProviderFactory implements PasswordPolicyProviderFactory {
```
- `public`: Can be accessed from anywhere
- `class`: Defines a new class
- `implements`: Says "this class follows the rules defined by this interface"
- By implementing `PasswordPolicyProviderFactory`, we promise to provide certain methods

```java
public static final String ID = "custom-password-policy";
```
- `public`: Anyone can read this
- `static`: Belongs to the class, not instances
- `final`: Cannot be changed
- This ID is what appears in Keycloak's admin console dropdown

```java
public static final String CONFIG_TYPE = "int";
```
- Defines the type of configuration value
- `"int"` = user enters a number
- Other options: `"boolean"`, `"String"`

```java
public static final String DEFAULT_VALUE = "12";
```
- The default value shown in the admin console
- Even though CONFIG_TYPE is "int", this is a String (Keycloak converts it)

```java
@Override
public PasswordPolicyProvider create(KeycloakSession session) {
    return new CustomPasswordPolicyProvider(session.getContext());
}
```
- `@Override`: Says "this method comes from the interface"
- `create()`: Keycloak calls this to get a Provider instance
- `KeycloakSession session`: Current request context
- Returns a new Provider instance
- This is called EVERY time a password needs validation

```java
@Override
public void init(Config.Scope config) {
    // Called once when Keycloak starts
    // Use this to load configuration files, initialize resources, etc.
}
```

```java
@Override
public void postInit(KeycloakSessionFactory factory) {
    // Called once after all providers are initialized
    // Use this if you need to interact with other providers
}
```

```java
@Override
public void close() {
    // Called when Keycloak shuts down
    // Use this to clean up resources
}
```

```java
@Override
public String getId() {
    return ID;
}
```
- Returns the unique ID of your policy
- This is how Keycloak identifies your plugin

```java
@Override
public String getDisplayName() {
    return DISPLAY_NAME;
}
```
- Returns the human-readable name
- Shown in the admin console dropdown

```java
@Override
public String getConfigType() {
    return CONFIG_TYPE;
}
```
- Tells Keycloak what type of input field to show

```java
@Override
public String getDefaultConfigValue() {
    return DEFAULT_VALUE;
}
```
- The default value for the config input

```java
@Override
public boolean isMultiplSupported() {
    return false;
}
```
- If `true`, users can add this policy multiple times with different configs
- For password policies, usually `false`

**Complete Factory class:**

```java
package com.keycloak.policy;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PasswordPolicyProviderFactory;

public class CustomPasswordPolicyProviderFactory implements PasswordPolicyProviderFactory {

    public static final String ID = "custom-password-policy";
    public static final String DISPLAY_NAME = "Custom Password Policy";
    public static final String CONFIG_TYPE = "int";
    public static final String DEFAULT_VALUE = "12";

    @Override
    public PasswordPolicyProvider create(KeycloakSession session) {
        return new CustomPasswordPolicyProvider(session.getContext());
    }

    @Override
    public void init(Config.Scope config) {
        // Initialization logic (if needed)
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Post-initialization logic (if needed)
    }

    @Override
    public void close() {
        // Cleanup logic (if needed)
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getConfigType() {
        return CONFIG_TYPE;
    }

    @Override
    public String getDefaultConfigValue() {
        return DEFAULT_VALUE;
    }

    @Override
    public boolean isMultiplSupported() {
        return false;
    }
}
```

### Step 4: Create the Provider Class

File: `src/main/java/com/keycloak/policy/CustomPasswordPolicyProvider.java`

This is where your password validation logic lives. Key concepts:

```java
public class CustomPasswordPolicyProvider implements PasswordPolicyProvider {
```
- Implements the `PasswordPolicyProvider` interface
- This is the "worker" class that does the validation

```java
private final KeycloakContext context;
```
- `private`: Only this class can access it
- `final`: Cannot be changed after construction
- Stores the Keycloak context (request info, locale, etc.)

```java
public CustomPasswordPolicyProvider(KeycloakContext context) {
    this.context = context;
}
```
- Constructor: Called when Factory creates a new instance
- Receives the context from the Factory's `create()` method

```java
@Override
public PolicyError validate(String username, String password) {
    return validate(null, null, password);
}
```
- One of two `validate()` methods
- This one only has username and password
- We delegate to the more detailed version

```java
@Override
public PolicyError validate(RealmModel realm, UserModel user, String password) {
    // Your validation logic here
}
```
- The main validation method
- `RealmModel realm`: The current realm (can be null)
- `UserModel user`: The user whose password is being changed (can be null)
- `String password`: The new password to validate
- Returns `PolicyError` if invalid, `null` if valid

**Understanding PolicyError:**

```java
return new PolicyError("Error message shown to user");
```
- Creates an error with a message
- Keycloak displays this message to the user
- Supports HTML: use `<br/>` for line breaks

```java
return null;
```
- Means "password is valid"
- No error, validation passes

**Accessing User Information:**

```java
String username = user.getUsername();
String firstName = user.getFirstName();
String lastName = user.getLastName();
String cnAttribute = user.getFirstAttribute("cn");
```
- `getUsername()`: User's login name
- `getFirstName()`: First name from user profile
- `getLastName()`: Last name from user profile
- `getFirstAttribute("cn")`: Custom attribute (from LDAP)

**Detecting User Locale:**

```java
private boolean isPortuguese(UserModel user) {
    if (context != null && user != null) {
        String locale = context.resolveLocale(user).getLanguage();
        return "pt".equals(locale);
    }
    return false;
}
```
- Checks if user's language is Portuguese
- Used to show error messages in the user's language

**Simplified Provider skeleton:**

```java
package com.keycloak.policy;

import org.keycloak.models.KeycloakContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PolicyError;

public class CustomPasswordPolicyProvider implements PasswordPolicyProvider {

    private static final int MIN_LENGTH = 12;
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
        // Check minimum length
        if (password == null || password.length() < MIN_LENGTH) {
            return new PolicyError(
                String.format("Password must be at least %d characters", MIN_LENGTH)
            );
        }

        // Check if password contains username
        if (user != null && user.getUsername() != null) {
            if (password.toLowerCase().contains(user.getUsername().toLowerCase())) {
                return new PolicyError("Password cannot contain username");
            }
        }

        // Password is valid
        return null;
    }

    @Override
    public Object parseConfig(String value) {
        return value != null && !value.isEmpty() ? Integer.valueOf(value) : MIN_LENGTH;
    }

    @Override
    public void close() {
        // Cleanup if needed
    }
}
```

### Step 5: Create the Service Descriptor

File: `src/main/resources/META-INF/services/org.keycloak.policy.PasswordPolicyProviderFactory`

**Important:** The filename IS the interface name, NOT a file extension!

```
com.keycloak.policy.CustomPasswordPolicyProviderFactory
```

**What this does:**
1. Java's ServiceLoader reads this file at runtime
2. It finds your Factory class name
3. It instantiates your Factory
4. Your plugin is now registered!

**Critical rules:**
- Filename MUST be exactly: `org.keycloak.policy.PasswordPolicyProviderFactory`
- Content MUST be the fully-qualified class name of your Factory
- One class name per line (if you have multiple)
- No extra spaces or line breaks

### Step 6: Build the Project

```bash
mvn clean package
```

**What happens:**

1. `mvn clean`: Deletes the `target/` directory
2. `mvn package`: 
   - Compiles Java files to `.class` files
   - Copies resources to `target/classes/`
   - Packages everything into a JAR
   - JAR location: `target/custom-password-policy-1.0.0.jar`

**If build fails:**
- Check Java version: `java -version` (must be 17+)
- Check Maven version: `mvn -version` (must be 3.6+)
- Check for typos in package names
- Ensure all files are in correct directories

---

## Understanding Each Component

### The Provider (Worker)

**Purpose:** Validates a single password

**Lifecycle:**
1. Created by Factory when needed
2. `validate()` called with password
3. Returns error or null
4. `close()` called
5. Garbage collected

**Key Methods:**

- `validate(realm, user, password)`: Main validation logic
- `parseConfig(String value)`: Parses the config value from admin console
- `close()`: Cleanup resources

**When to use which validate() method:**

```java
// Simple validation (no user context needed)
public PolicyError validate(String username, String password) {
    if (password.length() < 8) {
        return new PolicyError("Too short");
    }
    return null;
}

// Complex validation (needs user info)
public PolicyError validate(RealmModel realm, UserModel user, String password) {
    String fullName = user.getFirstAttribute("cn");
    if (password.contains(fullName)) {
        return new PolicyError("Cannot contain name");
    }
    return null;
}
```

### The Factory (Manager)

**Purpose:** Creates Provider instances and manages configuration

**Lifecycle:**
1. Created once when Keycloak starts
2. `init()` called
3. `postInit()` called
4. Lives in memory
5. `create()` called many times (creates Providers)
6. `close()` called when Keycloak stops

**Key Methods:**

- `create(session)`: Creates a Provider instance
- `getId()`: Unique identifier
- `getDisplayName()`: Human-readable name
- `getConfigType()`: Type of configuration ("int", "boolean", "String")
- `getDefaultConfigValue()`: Default config value

**Configuration Types:**

```java
// Integer input (e.g., minimum length)
public String getConfigType() {
    return "int";
}

// Boolean checkbox (e.g., enable/disable)
public String getConfigType() {
    return "boolean";
}

// Text input (e.g., regex pattern)
public String getConfigType() {
    return "String";
}
```

### The Service Descriptor (Registry)

**Purpose:** Tells Java's ServiceLoader about your plugin

**How it works:**
1. Java reads all files in `META-INF/services/`
2. Filename = Interface name
3. Content = Implementation class name(s)
4. ServiceLoader.load() instantiates your class

**Multiple implementations:**

```
com.keycloak.policy.CustomPasswordPolicyProviderFactory
com.keycloak.policy.AnotherPasswordPolicyProviderFactory
```

---

## How Components Connect

### The Complete Flow

```
1. Keycloak Startup
   └─→ Reads META-INF/services/org.keycloak.policy.PasswordPolicyProviderFactory
       └─→ Finds: com.keycloak.policy.CustomPasswordPolicyProviderFactory
           └─→ Instantiates Factory
               └─→ Calls factory.init(config)
                   └─→ Calls factory.postInit(sessionFactory)
                       └─→ Factory is ready

2. Admin Configures Policy
   └─→ Sees factory.getDisplayName() in dropdown: "Custom Password Policy"
       └─→ Sees input type from factory.getConfigType(): "int"
           └─→ Sees default from factory.getDefaultConfigValue(): "12"
               └─→ Admin sets value and saves

3. User Changes Password
   └─→ Keycloak calls factory.create(session)
       └─→ Factory returns new Provider instance
           └─→ Keycloak calls provider.validate(realm, user, password)
               ├─→ Provider checks password
               ├─→ Returns PolicyError (invalid) or null (valid)
               └─→ Keycloak calls provider.close()
                   └─→ Provider is garbage collected

4. Keycloak Shutdown
   └─→ Calls factory.close()
       └─→ Factory cleans up resources
           └─→ Factory is garbage collected
```

### Data Flow Diagram

```
                    ┌────────────────────────┐
                    │   Service Descriptor   │
                    │    (Registry File)     │
                    └───────────┬────────────┘
                                │
                                │ Points to
                                ▼
                    ┌────────────────────────┐
                    │   Factory Class        │
                    │  (Long-lived Manager)  │
                    │                        │
                    │  - getId()             │
                    │  - getDisplayName()    │
                    │  - getConfigType()     │
                    │  - create(session) ──────┐
                    └────────────────────────┘ │
                                               │ Creates
                                               ▼
                                   ┌────────────────────────┐
                                   │   Provider Instance    │
                                   │  (Short-lived Worker)  │
                                   │                        │
                                   │  - validate()          │
                                   │  - Returns error/null  │
                                   │  - close()             │
                                   └────────────────────────┘
```

### Communication Example

**Scenario:** User "john" tries to set password "john123"

```java
// 1. Keycloak needs a Provider
KeycloakSession session = /* current session */;
PasswordPolicyProvider provider = factory.create(session);

// 2. Keycloak calls validate
UserModel john = session.users().getUserByUsername("john");
PolicyError error = provider.validate(realm, john, "john123");

// 3. Provider checks password
String username = john.getUsername(); // "john"
if ("john123".contains(username)) {
    return new PolicyError("Password cannot contain username");
}

// 4. Keycloak receives error and shows to user
if (error != null) {
    showError(error.getMessage());
}

// 5. Keycloak cleans up
provider.close();
```

---

## Testing Your Plugin

### Unit Testing (Optional but Recommended)

Create `src/test/java/com/keycloak/policy/CustomPasswordPolicyProviderTest.java`:

```java
package com.keycloak.policy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomPasswordPolicyProviderTest {

    @Test
    void testShortPassword() {
        CustomPasswordPolicyProvider provider = 
            new CustomPasswordPolicyProvider(null);
        
        PolicyError error = provider.validate("user", "short");
        
        assertNotNull(error, "Short password should fail");
        assertTrue(error.getMessage().contains("12 characters"));
    }

    @Test
    void testValidPassword() {
        CustomPasswordPolicyProvider provider = 
            new CustomPasswordPolicyProvider(null);
        
        PolicyError error = provider.validate("user", "ValidPass123!");
        
        assertNull(error, "Valid password should pass");
    }
}
```

### Integration Testing

1. **Build the JAR:**
   ```bash
   mvn clean package
   ```

2. **Deploy to test Keycloak:**
   ```bash
   cp target/custom-password-policy-1.0.0.jar /path/to/keycloak/providers/
   ```

3. **Check Keycloak logs:**
   ```bash
   tail -f /path/to/keycloak/data/log/keycloak.log
   ```
   
   Look for: `custom-password-policy (com.keycloak.policy.CustomPasswordPolicyProviderFactory)`

4. **Test in admin console:**
   - Add policy to realm
   - Try setting various passwords
   - Verify error messages appear correctly

### Common Issues and Solutions

**Plugin not appearing in dropdown:**
- Check service descriptor filename (must be exact)
- Check class name in service descriptor (must be fully-qualified)
- Check Keycloak logs for errors
- Ensure JAR is in `providers/` directory
- Restart Keycloak

**Compilation errors:**
- Verify Java 17 is installed
- Check Keycloak version matches pom.xml
- Run `mvn clean` first
- Check import statements

**PolicyError not showing:**
- Check if policy is enabled in realm
- Verify LDAP federation has "Validate Password Policy" ON
- Check browser console for JavaScript errors

**Locale detection not working:**
- Ensure user has language preference set
- Check Keycloak theme supports the locale
- Verify Accept-Language header in browser

---

## Advanced Topics

### Adding Configuration Parameters

You can add custom configuration that admins can set:

```java
// In Factory
@Override
public String getConfigType() {
    return "String"; // Admin enters text
}

// In Provider
@Override
public Object parseConfig(String value) {
    // Parse the admin's input
    return value.split(","); // e.g., "word1,word2,word3"
}
```

### Accessing Keycloak Services

```java
// In Provider's validate method
KeycloakSession session = /* get from context */;

// Access user storage
UserProvider users = session.users();
UserModel user = users.getUserByUsername("john");

// Access realm settings
RealmModel realm = session.getContext().getRealm();
String realmName = realm.getName();
```

### Internationalization (i18n)

For production plugins, use Keycloak's i18n system:

1. Create `messages_en.properties`:
   ```properties
   password-too-short=Password must be at least {0} characters
   ```

2. Create `messages_pt.properties`:
   ```properties
   password-too-short=A senha deve ter pelo menos {0} caracteres
   ```

3. Use in code:
   ```java
   String message = session.getContext()
       .resolveLocale(user)
       .getMessage("password-too-short", minLength);
   ```

---

## Summary

**Key Takeaways:**

1. **Factory = Manager**: Lives long, creates Providers, handles config
2. **Provider = Worker**: Lives short, validates one password, gets destroyed
3. **Service Descriptor = Registry**: Tells Keycloak your plugin exists
4. **Package structure matters**: Follow Java/Maven conventions
5. **Test thoroughly**: Unit tests + integration tests
6. **Read Keycloak docs**: SPI documentation is your friend

**Next Steps:**

1. Study the complete source code of this project
2. Modify the validation logic to understand how it works
3. Add logging to see when methods are called
4. Create your own custom policy from scratch
5. Read Keycloak's SPI documentation for other extension points

**Resources:**

- Keycloak SPI Docs: https://www.keycloak.org/docs/latest/server_development/
- Maven Guide: https://maven.apache.org/guides/getting-started/
- Java SPI Tutorial: https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html

Good luck building your Keycloak plugins! 🚀
