# RESTful API Design Standards

## REST Principles

### Resource-Oriented Design

- Use nouns for resource names
- Use HTTP methods for actions
- Use plural nouns for collections
- Use consistent URL structure
- Follow hierarchical relationships

### HTTP Methods

| Method | Safe | Idempotent | Purpose |
|--------|------|------------|---------|
| GET | Yes | Yes | Retrieve resource |
| POST | No | No | Create resource |
| PUT | No | Yes | Update/Replace entire resource |
| PATCH | No | No | Partial update |
| DELETE | No | Yes | Delete resource |
| OPTIONS | Yes | Yes | Describe communication options |
| HEAD | Yes | Yes | Retrieve headers only |

## URL Design

### Base URL Structure

```
https://api.example.com/v1
```

### Resource Naming

```bash
# Use plural nouns for collections
GET /users
POST /users
GET /posts
POST /posts

# Use singular for specific items
GET /users/{user_id}
PUT /users/{user_id}
DELETE /users/{user_id}

# Use lowercase with hyphens
GET /blog-posts
GET /user-profiles

# Avoid verbs in URLs (except search/filter)
# Bad:
GET /getUsers
POST /createUser

# Good:
GET /users
POST /users
```

### Hierarchy and Relationships

```bash
# One-to-many relationships
GET /users/{user_id}/posts
POST /users/{user_id}/posts
GET /users/{user_id}/posts/{post_id}

# Nested resources (maximum 2-3 levels)
GET /users/{user_id}/posts/{post_id}/comments
POST /users/{user_id}/posts/{post_id}/comments

# Alternative: Use query parameters for deep nesting
GET /comments?user_id={user_id}&post_id={post_id}
```

### Query Parameters

```bash
# Pagination
GET /posts?page=1&page_size=20
GET /posts?offset=0&limit=20

# Filtering
GET /posts?status=published
GET /users?is_active=true
GET /products?category=electronics&price_min=100

# Sorting
GET /posts?sort=-created_at  # Descending
GET /posts?sort=created_at   # Ascending
GET /posts?sort=created_at,title

# Searching
GET /posts?search=fastapi
GET /users?q=john

# Field selection (partial response)
GET /users?fields=id,name,email

# Filtering with ranges
GET /products?price_min=100&price_max=500
GET /logs?date_from=2024-01-01&date_to=2024-12-31
```

## Request and Response Format

### Standard Response Format

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "John Doe",
    "email": "john@example.com"
  },
  "message": "User retrieved successfully",
  "meta": {
    "timestamp": "2024-03-22T16:00:00Z",
    "request_id": "req_abc123"
  }
}
```

### List Response Format

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "John Doe",
      "email": "john@example.com"
    },
    {
      "id": 2,
      "name": "Jane Smith",
      "email": "jane@example.com"
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total": 100,
    "total_pages": 5
  }
}
```

### Error Response Format

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Validation failed",
    "details": [
      {
        "field": "email",
        "message": "Invalid email format"
      },
      {
        "field": "password",
        "message": "Password must be at least 8 characters"
      }
    ]
  },
  "meta": {
    "timestamp": "2024-03-22T16:00:00Z",
    "request_id": "req_abc123"
  }
}
```

## HTTP Status Codes

### Success Codes

| Code | Name | Usage |
|------|------|-------|
| 200 | OK | Request succeeded |
| 201 | Created | Resource created successfully |
| 202 | Accepted | Request accepted, processing asynchronously |
| 204 | No Content | Request succeeded, no content returned |

### Client Error Codes

| Code | Name | Usage |
|------|------|-------|
| 400 | Bad Request | Invalid request data |
| 401 | Unauthorized | Authentication required |
| 403 | Forbidden | User lacks permission |
| 404 | Not Found | Resource not found |
| 405 | Method Not Allowed | HTTP method not supported |
| 409 | Conflict | Resource conflict (duplicate) |
| 422 | Unprocessable Entity | Validation failed |
| 429 | Too Many Requests | Rate limit exceeded |

### Server Error Codes

| Code | Name | Usage |
|------|------|-------|
| 500 | Internal Server Error | Unexpected server error |
| 502 | Bad Gateway | Upstream service error |
| 503 | Service Unavailable | Service temporarily down |
| 504 | Gateway Timeout | Upstream timeout |

### Error Code Standards

```json
{
  "success": false,
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "User with id 123 not found"
  }
}
```

Common error codes:
- `VALIDATION_ERROR` - Invalid request data
- `AUTHENTICATION_FAILED` - Invalid credentials
- `AUTHORIZATION_FAILED` - Insufficient permissions
- `RESOURCE_NOT_FOUND` - Resource doesn't exist
- `DUPLICATE_RESOURCE` - Resource already exists
- `RATE_LIMIT_EXCEEDED` - Too many requests
- `INTERNAL_ERROR` - Unexpected server error

## Versioning

### URL Versioning (Recommended)

```bash
https://api.example.com/v1/users
https://api.example.com/v2/users
```

### Header Versioning

```bash
GET /users
Header: Accept: application/vnd.example.v1+json
```

### Query Parameter Versioning (Not Recommended)

```bash
GET /users?version=1
```

## Authentication

### Bearer Token (JWT)

```bash
# Request
GET /users
Header: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# Token format
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

### API Key

```bash
GET /users
Header: X-API-Key: abc123xyz789
```

## Data Validation

### Request Validation

```json
// POST /users
{
  "email": "john@example.com",
  "password": "securepassword123",
  "first_name": "John",
  "last_name": "Doe"
}

// Validation Rules:
// - email: required, valid email format, unique
// - password: required, min 8 chars, at least 1 uppercase, 1 lowercase, 1 number
// - first_name: required, max 100 chars
// - last_name: required, max 100 chars
```

### Response Validation

```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "john@example.com",
    "first_name": "John",
    "last_name": "Doe",
    "created_at": "2024-03-22T16:00:00Z",
    "updated_at": "2024-03-22T16:00:00Z"
  }
}

// Never return sensitive data:
// - passwords
// - security tokens
// - internal IDs
// - personal information (unless authorized)
```

## Pagination

### Page-Based Pagination

```bash
GET /users?page=1&page_size=20

# Response
{
  "success": true,
  "data": [...],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total": 100,
    "total_pages": 5,
    "has_next": true,
    "has_prev": false
  }
}
```

### Cursor-Based Pagination (For Large Datasets)

```bash
GET /users?cursor=eyJpZCI6MjB9&limit=20

# Response
{
  "success": true,
  "data": [...],
  "pagination": {
    "next_cursor": "eyJpZCI6NDB9",
    "has_next": true
  }
}
```

## Filtering, Sorting, and Searching

### Filtering

```bash
# Exact match
GET /products?category=electronics

# Multiple filters
GET /products?category=electronics&brand=apple

# Boolean filters
GET /users?is_active=true&is_verified=true

# Range filters
GET /products?price_min=100&price_max=500
GET /orders?date_from=2024-01-01&date_to=2024-12-31

# Array filters
GET /products?tags=python&tags=fastapi
```

### Sorting

```bash
# Single field ascending
GET /posts?sort=created_at

# Single field descending
GET /posts?sort=-created_at

# Multiple fields
GET /posts?sort=-created_at,title

# Using query parameter
GET /posts?sort_by=created_at&sort_order=desc
```

### Searching

```bash
# Full-text search
GET /posts?search=fastapi tutorial

# Search in specific fields
GET /users?q=john&search_fields=name,email

# Fuzzy search
GET /products?q=iphone&fuzzy=true
```

## CRUD Operations

### Create (POST)

```bash
POST /users
Content-Type: application/json

{
  "email": "john@example.com",
  "password": "securepassword123",
  "first_name": "John",
  "last_name": "Doe"
}

# Response: 201 Created
{
  "success": true,
  "data": {
    "id": 1,
    "email": "john@example.com",
    "first_name": "John",
    "last_name": "Doe",
    "created_at": "2024-03-22T16:00:00Z"
  }
}
```

### Read (GET)

```bash
# List all resources
GET /users

# Get specific resource
GET /users/{user_id}

# Response: 200 OK
{
  "success": true,
  "data": {
    "id": 1,
    "email": "john@example.com",
    "first_name": "John",
    "last_name": "Doe",
    "created_at": "2024-03-22T16:00:00Z"
  }
}
```

### Update (PUT)

```bash
PUT /users/{user_id}
Content-Type: application/json

{
  "email": "john.new@example.com",
  "password": "newpassword123",
  "first_name": "John",
  "last_name": "Doe"
}

# Response: 200 OK
{
  "success": true,
  "data": {
    "id": 1,
    "email": "john.new@example.com",
    "first_name": "John",
    "last_name": "Doe",
    "updated_at": "2024-03-22T17:00:00Z"
  }
}
```

### Partial Update (PATCH)

```bash
PATCH /users/{user_id}
Content-Type: application/json

{
  "email": "john.updated@example.com"
}

# Response: 200 OK
{
  "success": true,
  "data": {
    "id": 1,
    "email": "john.updated@example.com",
    "first_name": "John",
    "last_name": "Doe",
    "updated_at": "2024-03-22T17:00:00Z"
  }
}
```

### Delete (DELETE)

```bash
DELETE /users/{user_id}

# Response: 200 OK or 204 No Content
{
  "success": true,
  "message": "User deleted successfully"
}
```

## Content Types

### Request Content Types

```bash
# JSON (most common)
Content-Type: application/json

# Form data
Content-Type: application/x-www-form-urlencoded

# Multipart form data (file uploads)
Content-Type: multipart/form-data

# XML
Content-Type: application/xml
```

### Response Content Types

```bash
# JSON
Content-Type: application/json

# PDF
Content-Type: application/pdf

# CSV
Content-Type: text/csv

# Binary data
Content-Type: application/octet-stream

# File download
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="example.pdf"
```

## Rate Limiting

### Rate Limit Headers

```bash
GET /users
Status: 200 OK
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 999
X-RateLimit-Reset: 1711123200
```

### Rate Limit Exceeded

```json
{
  "success": false,
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Try again in 60 seconds.",
    "retry_after": 60
  }
}
```

## CORS (Cross-Origin Resource Sharing)

### CORS Headers

```bash
Access-Control-Allow-Origin: https://example.com
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Max-Age: 86400
```

### Preflight Request

```bash
OPTIONS /users
Access-Control-Request-Method: POST
Access-Control-Request-Headers: Content-Type

# Response
Access-Control-Allow-Origin: https://example.com
Access-Control-Allow-Methods: POST
Access-Control-Allow-Headers: Content-Type
```

## Caching

### Cache Headers

```bash
# Cacheable resource
Cache-Control: public, max-age=3600
ETag: "abc123"

# Non-cacheable resource
Cache-Control: no-store, no-cache, must-revalidate

# Conditional request
GET /users/1
If-None-Match: "abc123"

# Response: 304 Not Modified (if not changed)
# Response: 200 OK (if changed)
```

## Best Practices

### Security

1. **Always use HTTPS** in production
2. **Validate and sanitize** all input
3. **Never expose sensitive data** in responses
4. **Implement rate limiting** to prevent abuse
5. **Use strong authentication** (JWT, OAuth2)
6. **Implement CORS** properly
7. **Log security events** for monitoring
8. **Regular security audits** and updates

### Performance

1. **Use pagination** for large result sets
2. **Implement caching** for frequently accessed data
3. **Use compression** (gzip) for large payloads
4. **Optimize database queries** with indexes
5. **Use async operations** where possible
6. **Monitor API performance** continuously
7. **Implement CDN** for static assets

### Documentation

1. **Use OpenAPI/Swagger** for API documentation
2. **Provide clear examples** for all endpoints
3. **Document error codes** and their meanings
4. **Keep documentation updated** with code changes
5. **Include versioning** information
6. **Provide SDKs** for popular languages

### Testing

1. **Write unit tests** for all endpoints
2. **Write integration tests** for workflows
3. **Test error scenarios** thoroughly
4. **Load test** for performance
5. **Security test** for vulnerabilities
6. **Document test cases** clearly

### Monitoring

1. **Log all API requests** with timestamps
2. **Track response times** and error rates
3. **Monitor system resources** (CPU, memory, disk)
4. **Set up alerts** for critical failures
5. **Use APM tools** for deeper insights
6. **Regular performance reviews**

## Common API Patterns

### Registration and Authentication

```bash
# Register
POST /auth/register
{
  "email": "user@example.com",
  "password": "securepassword123"
}

# Login
POST /auth/login
{
  "email": "user@example.com",
  "password": "securepassword123"
}

# Logout
POST /auth/logout
Header: Authorization: Bearer {token}

# Refresh token
POST /auth/refresh
{
  "refresh_token": "abc123xyz789"
}

# Verify email
POST /auth/verify-email
{
  "token": "abc123xyz789"
}

# Forgot password
POST /auth/forgot-password
{
  "email": "user@example.com"
}

# Reset password
POST /auth/reset-password
{
  "token": "abc123xyz789",
  "new_password": "newpassword123"
}
```

### CRUD with Relationships

```bash
# Create post for user
POST /users/{user_id}/posts
{
  "title": "My First Post",
  "content": "This is my first post content"
}

# Get user's posts
GET /users/{user_id}/posts

# Get post's comments
GET /posts/{post_id}/comments

# Add comment to post
POST /posts/{post_id}/comments
{
  "content": "Great post!"
}
```

### Bulk Operations

```bash
# Bulk create
POST /users/bulk
{
  "users": [
    {"email": "user1@example.com", "name": "User 1"},
    {"email": "user2@example.com", "name": "User 2"}
  ]
}

# Bulk update
PATCH /users/bulk
{
  "ids": [1, 2, 3],
  "updates": {
    "is_active": true
  }
}

# Bulk delete
DELETE /users/bulk?ids=1,2,3
```

### File Uploads

```bash
# Single file upload
POST /files
Content-Type: multipart/form-data

file: <binary data>
metadata: {"name": "document.pdf", "category": "documents"}

# Response
{
  "success": true,
  "data": {
    "id": 1,
    "name": "document.pdf",
    "url": "https://cdn.example.com/files/document.pdf",
    "size": 102400,
    "content_type": "application/pdf"
  }
}

# Multiple file upload
POST /files/bulk
Content-Type: multipart/form-data

files: [<binary data>, <binary data>]
metadata: {"category": "documents"}
```

## OpenAPI/Swagger Specification Example

```yaml
openapi: 3.0.0
info:
  title: My API
  version: 1.0.0
  description: Sample API specification

servers:
  - url: https://api.example.com/v1

paths:
  /users:
    get:
      summary: List all users
      parameters:
        - name: page
          in: query
          schema:
            type: integer
            default: 1
        - name: page_size
          in: query
          schema:
            type: integer
            default: 20
      responses:
        '200':
          description: Successful response
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                  data:
                    type: array
                    items:
                      $ref: '#/components/schemas/User'
                  pagination:
                    $ref: '#/components/schemas/Pagination'
    
    post:
      summary: Create a new user
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UserCreate'
      responses:
        '201':
          description: User created successfully
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                  data:
                    $ref: '#/components/schemas/User'

  /users/{user_id}:
    get:
      summary: Get user by ID
      parameters:
        - name: user_id
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: Successful response
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                  data:
                    $ref: '#/components/schemas/User'
        '404':
          description: User not found

components:
  schemas:
    User:
      type: object
      properties:
        id:
          type: integer
        email:
          type: string
          format: email
        first_name:
          type: string
        last_name:
          type: string
        created_at:
          type: string
          format: date-time
    
    UserCreate:
      type: object
      required:
        - email
        - password
      properties:
        email:
          type: string
          format: email
        password:
          type: string
          minLength: 8
        first_name:
          type: string
        last_name:
          type: string
    
    Pagination:
      type: object
      properties:
        page:
          type: integer
        page_size:
          type: integer
        total:
          type: integer
        total_pages:
          type: integer
```
