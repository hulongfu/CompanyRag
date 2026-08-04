public class GenPass {
    public static void main(String[] args) {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String raw = "admin123";
        String encoded = encoder.encode(raw);
        System.out.println("Raw password: " + raw);
        System.out.println("BCrypt encoded: " + encoded);
        System.out.println("Verify match: " + encoder.matches(raw, encoded));
    }
}
