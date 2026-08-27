# Code Generator Skill

Automatically generate Python/FastAPI code from database schemas, API definitions, JSON Schema, or natural language descriptions.

## Features

- **Multiple Input Formats**: Database tables, OpenAPI/Swagger, JSON Schema, natural language
- **Complete Project Templates**: Full FastAPI project structure with best practices
- **Multiple Code Types**: SQL scripts, RESTful clients, ORM models, CRUD operations, validation logic, GraphQL
- **Target Framework**: Python/FastAPI with comprehensive conventions

## Supported Inputs

### 1. Database Table Structures
- SQL CREATE TABLE statements
- Database schema descriptions
- ORM model definitions
- Natural language descriptions

### 2. API Definitions (OpenAPI/Swagger)
- OpenAPI 3.0/3.1 specifications
- Swagger JSON/YAML files
- API endpoint definitions

### 3. JSON Schema
- JSON Schema definitions
- Type definitions
- Data validation rules

### 4. Natural Language Descriptions
- Plain English/Chinese descriptions
- Feature requirements
- Business logic descriptions

## Supported Outputs

### Database SQL Scripts
- DDL statements (CREATE TABLE, ALTER, INDEX)
- Migration scripts
- Database initialization scripts

### RESTful API Clients
- HTTP client implementations
- Request/response models
- Error handling

### ORM Models
- SQLAlchemy models
- Prisma schemas
- TypeORM entities
- Sequelize models

### CRUD Operations
- Create, Read, Update, Delete endpoints
- Business logic layer
- Service layer implementations

### Data Validation Logic
- Pydantic models for FastAPI
- Input validation schemas
- Custom validators

### GraphQL
- Schema definitions
- Resolver implementations
- Query and mutation operations

## Usage Examples

### Example 1: Generate CRUD API from Database Schema

**Input**:
```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**Output**: Complete FastAPI project with:
- SQLAlchemy models
- Pydantic schemas
- CRUD operations
- API endpoints
- Database migrations

### Example 2: Generate API Client from OpenAPI Spec

**Input**: OpenAPI 3.0 JSON/YAML specification

**Output**: Python HTTP client with:
- Typed models
- Async methods
- Error handling
- Authentication support

### Example 3: Generate ORM Models from JSON Schema

**Input**: JSON Schema definitions

**Output**: Prisma/TypeORM/SQLAlchemy model definitions with:
- Field types and constraints
- Relationships
- Indexes

### Example 4: Generate Project from Natural Language

**Input**: "Create a blog API with posts, comments, and users"

**Output**: Complete FastAPI project with:
- Database models
- API endpoints
- Authentication
- Documentation

## Project Structure

Generated projects follow FastAPI best practices:

```
project_name/
├── app/
│   ├── main.py                 # Application entry point
│   ├── api/                    # API routes
│   │   └── v1/
│   │       ├── api.py
│   │       └── endpoints/
│   ├── core/                   # Core logic
│   │   ├── config.py
│   │   └── security.py
│   ├── crud/                   # Database operations
│   ├── models/                 # SQLAlchemy models
│   ├── schemas/                # Pydantic schemas
│   └── services/               # Business logic
├── tests/
├── alembic/                    # Migrations
├── requirements.txt
├── .env.example
└── README.md
```

## Templates and References

### Templates (assets/templates/)
- `fastapi_project/` - Complete FastAPI project templates
- `orm_models/` - ORM model templates (SQLAlchemy, Prisma, TypeORM)
- `api_clients/` - RESTful client implementations
- `graphql/` - GraphQL schema and resolver templates
- `validation/` - Pydantic model templates

### References (references/)
- `fastapi_conventions.md` - FastAPI project structure and conventions
- `database_patterns.md` - Database design patterns and best practices
- `api_standards.md` - RESTful API design standards

## Best Practices

### Code Generation
- Clean, readable code with documentation
- Type hints and Pydantic models
- Comprehensive error handling
- Security (authentication, input validation)
- Testing templates included

### FastAPI Conventions
- Async/await for I/O operations
- Dependency injection
- Pydantic models for validation
- APIRouter for route organization
- Separation of concerns
- Environment variables for configuration

### Database Patterns
- Appropriate field types and constraints
- Indexes for performance
- Foreign key relationships
- Timestamps (created_at, updated_at)
- Migration support

## Installation

This skill is automatically available in WorkBuddy when installed in `~/.workbuddy/skills/code-generator/`.

## Development

To extend or modify this skill:

1. Edit `SKILL.md` to update workflows
2. Add new templates in `assets/templates/`
3. Update reference docs in `references/`
4. Test with various input formats

## License

This skill is provided as-is for use with WorkBuddy.
