---
name: code-generator
description: Automatically generate Python/FastAPI code from database schemas, API definitions, JSON Schema, or natural language descriptions. Use this skill when users need to generate database SQL scripts, RESTful API clients, ORM models (Sequelize, TypeORM, Prisma), CRUD operations, data validation logic, or GraphQL queries/mutations. This skill supports multiple input formats and generates complete FastAPI project templates with best practices.
---

# Code Generator Skill

This skill provides code generation capabilities for Python/FastAPI applications, transforming various input formats into production-ready code with complete project templates.

## When to Use This Skill

Trigger this skill when users request:
- Generate code from database table structures
- Create API clients from OpenAPI/Swagger specifications
- Generate ORM models from schemas
- Create CRUD operations from data definitions
- Generate data validation logic from JSON Schema
- Build GraphQL queries/mutations from types
- Generate complete FastAPI project templates from descriptions

## Supported Input Formats

### 1. Database Table Structures
Parse and process:
- SQL CREATE TABLE statements
- Database schema descriptions (ER diagrams, markdown tables)
- ORM model definitions
- Natural language table descriptions

### 2. API Definitions (OpenAPI/Swagger)
Process:
- OpenAPI 3.0/3.1 specifications
- Swagger JSON/YAML files
- API endpoint definitions
- Request/response schemas

### 3. JSON Schema
Validate and generate from:
- JSON Schema definitions
- Type definitions
- Data validation rules

### 4. Natural Language Descriptions
Interpret and generate from:
- Plain English/Chinese descriptions
- Feature requirements
- Business logic descriptions

## Supported Output Code Types

### Database SQL Scripts
- DDL statements (CREATE TABLE, ALTER, INDEX)
- Migration scripts
- Database initialization scripts
- Seed data scripts

### RESTful API Clients
- HTTP client implementations
- Request/response models
- Error handling
- Authentication integration

### ORM Models
- **Prisma**: TypeScript/Python schema definitions
- **TypeORM**: Entity classes with decorators
- **Sequelize**: Model definitions
- **SQLAlchemy**: Python model classes
- **Django ORM**: Model definitions

### CRUD Operations
- Create, Read, Update, Delete endpoints
- Business logic layer
- Service layer implementations
- Repository pattern implementations

### Data Validation Logic
- Pydantic models for FastAPI
- Input validation schemas
- Error handling
- Custom validators

### GraphQL Queries/Mutations
- Schema definitions
- Resolver implementations
- Query and mutation operations
- Type definitions

## Code Generation Workflow

### Step 1: Analyze Input

Identify the input format and extract relevant information:
- For database schemas: Extract table names, columns, types, relationships
- For API definitions: Extract endpoints, methods, parameters, responses
- For JSON Schema: Extract types, required fields, validation rules
- For natural language: Parse intent, entities, relationships

Load relevant reference materials if available:
- `references/database_patterns.md` - Common database design patterns
- `references/api_standards.md` - API design best practices
- `references/fastapi_conventions.md` - FastAPI project structure conventions

### Step 2: Determine Output Structure

Based on user requirements, determine what to generate:
- Single file vs complete project structure
- Which code types to include
- Target framework (FastAPI, Django, Flask, etc.)
- Dependencies and configurations needed

### Step 3: Generate Code Templates

Use bundled templates from `assets/templates/`:
- `fastapi_project/` - Complete FastAPI project structure
- `orm_models/` - ORM model templates for different frameworks
- `api_clients/` - RESTful client implementations
- `graphql/` - GraphQL schema and resolver templates
- `validation/` - Pydantic model templates

Customize templates based on:
- User preferences and requirements
- Project naming conventions
- Specific business rules

### Step 4: Implement Business Logic

Generate complete implementations including:
- Service layer with business logic
- Repository layer for data access
- Error handling and logging
- Input/output validation
- Documentation and type hints

### Step 5: Create Project Structure

Generate complete directory structure:
```
project_name/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── models/
│   ├── schemas/
│   ├── api/
│   │   ├── v1/
│   │   │   ├── endpoints/
│   ├── core/
│   │   ├── config.py
│   │   ├── security.py
│   ├── crud/
│   └── services/
├── tests/
├── alembic/
├── requirements.txt
├── .env.example
├── pyproject.toml
└── README.md
```

### Step 6: Generate Supporting Files

Create necessary configuration and documentation:
- `requirements.txt` - Python dependencies
- `.env.example` - Environment variables template
- `pyproject.toml` - Project metadata and tool configuration
- `README.md` - Project documentation
- `docker-compose.yml` - Docker configuration (optional)
- `Dockerfile` - Container image (optional)

### Step 7: Validate Generated Code

Ensure code quality:
- Verify syntax correctness
- Check type hints
- Validate imports and dependencies
- Confirm best practices adherence
- Test basic functionality (if possible)

## Using Bundled Templates

### Template Structure

Templates are organized by use case in `assets/templates/`:

#### FastAPI Project Templates (`assets/templates/fastapi_project/`)
Complete project scaffolds with:
- Application structure
- Configuration management
- Database setup
- API routing
- Authentication middleware
- CORS configuration

#### ORM Model Templates (`assets/templates/orm_models/`)
Framework-specific model definitions:
- `prisma_schema.prisma` - Prisma schema
- `typeorm_entity.ts` - TypeORM entity
- `sequelize_model.ts` - Sequelize model
- `sqlalchemy_model.py` - SQLAlchemy model
- `django_model.py` - Django ORM model

#### API Client Templates (`assets/templates/api_clients/`)
HTTP client implementations:
- `httpx_client.py` - HTTPX-based client
- `requests_client.py` - Requests-based client
- `aiohttp_client.py` - Async HTTP client

#### GraphQL Templates (`assets/templates/graphql/`)
GraphQL implementations:
- `schema.graphql` - GraphQL schema
- `resolver.py` - Resolver implementations
- `types.py` - Type definitions

#### Validation Templates (`assets/templates/validation/`)
Data validation models:
- `pydantic_model.py` - Pydantic model
- `pydantic_validator.py` - Custom validators

### Customizing Templates

When generating code:
1. Read the appropriate template file
2. Replace placeholders with actual values (table names, field names, types, etc.)
3. Add custom business logic as needed
4. Adjust imports and dependencies
5. Ensure consistent naming conventions

## Best Practices

### Code Generation Guidelines

1. **Maintainability**: Generate clean, readable code with proper documentation
2. **Type Safety**: Use type hints and Pydantic models for validation
3. **Error Handling**: Implement comprehensive error handling and logging
4. **Security**: Include proper authentication, authorization, and input validation
5. **Testing**: Include test templates and examples
6. **Documentation**: Generate clear docstrings and README files

### FastAPI Conventions

Follow FastAPI best practices:
- Use async/await for I/O operations
- Implement dependency injection
- Use Pydantic models for request/response validation
- Organize routes using APIRouter
- Separate concerns (models, schemas, CRUD, API)
- Use environment variables for configuration

### Database Best Practices

- Use appropriate field types and constraints
- Define indexes for frequently queried fields
- Implement foreign key relationships
- Include timestamps (created_at, updated_at)
- Use migrations for schema changes
- Consider soft delete functionality

### API Design Principles

- Use RESTful conventions
- Implement proper HTTP status codes
- Include pagination for list endpoints
- Use consistent naming conventions
- Provide clear error messages
- Include API documentation (auto-generated by FastAPI)

## Reference Materials

Load these references when needed for guidance:

- `references/database_patterns.md` - Database design patterns and best practices
- `references/api_standards.md` - RESTful API design standards
- `references/fastapi_conventions.md` - FastAPI project structure and conventions
- `references/orm_comparison.md` - Comparison of different ORM frameworks
- `references/validation_patterns.md` - Common validation patterns

## Error Handling and Edge Cases

Handle common scenarios:
- Invalid input formats - Provide clear error messages and suggestions
- Ambiguous specifications - Ask for clarification
- Conflicting requirements - Identify conflicts and propose solutions
- Missing dependencies - Include all required packages in requirements.txt
- Complex relationships - Handle one-to-many, many-to-many, etc.
- Circular dependencies - Implement proper dependency injection

## Examples

### Example 1: Generate CRUD API from Database Schema

Input: SQL CREATE TABLE statements
Output: Complete FastAPI project with models, schemas, CRUD operations, and API endpoints

### Example 2: Generate API Client from OpenAPI Spec

Input: OpenAPI 3.0 JSON/YAML
Output: Python HTTP client with typed models and methods for each endpoint

### Example 3: Generate ORM Models from JSON Schema

Input: JSON Schema definitions
Output: Prisma/TypeORM/SQLAlchemy model definitions with relationships

### Example 4: Generate Project from Natural Language

Input: "Create a blog API with posts, comments, and users"
Output: Complete FastAPI project with database models, API endpoints, and documentation
