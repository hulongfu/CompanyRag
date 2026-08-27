# FastAPI Project Conventions

## Project Structure

Standard FastAPI project layout:

```
project_name/
├── app/
│   ├── __init__.py
│   ├── main.py                 # Application entry point
│   ├── api/                    # API routes
│   │   ├── __init__.py
│   │   ├── deps.py             # Dependencies
│   │   └── v1/
│   │       ├── __init__.py
│   │       ├── api.py          # API router aggregation
│   │       └── endpoints/      # Individual route files
│   ├── core/                   # Core application logic
│   │   ├── __init__.py
│   │   ├── config.py           # Configuration settings
│   │   ├── security.py         # Security utilities (JWT, password hashing)
│   │   └── deps.py             # Shared dependencies
│   ├── crud/                   # Database operations
│   │   ├── __init__.py
│   │   ├── base.py             # Base CRUD class
│   │   └── crud_user.py        # Specific CRUD operations
│   ├── db/                     # Database setup
│   │   ├── __init__.py
│   │   ├── base.py             # Base declarative model
│   │   ├── session.py          # Database session management
│   │   └── init_db.py          # Database initialization
│   ├── models/                 # SQLAlchemy models
│   │   ├── __init__.py
│   │   └── user.py
│   ├── schemas/                # Pydantic schemas (DTOs)
│   │   ├── __init__.py
│   │   ├── user.py
│   │   └── token.py
│   ├── services/               # Business logic layer
│   │   ├── __init__.py
│   │   └── user_service.py
│   └── utils/                  # Utility functions
│       ├── __init__.py
│       └── logger.py
├── tests/                      # Test files
│   ├── __init__.py
│   ├── conftest.py             # Pytest configuration
│   └── test_api/
│       └── test_users.py
├── alembic/                    # Database migrations
│   ├── versions/
│   └── env.py
├── scripts/                    # Utility scripts
│   ├── init_db.py
│   └── migrate.sh
├── .env.example               # Environment variables template
├── .gitignore
├── requirements.txt           # Production dependencies
├── requirements-dev.txt       # Development dependencies
├── pyproject.toml             # Project metadata
└── README.md
```

## Naming Conventions

### Files and Directories
- Use lowercase with underscores: `user_service.py`, `api_v1/`
- Module files use singular nouns: `user.py` (not `users.py`)
- Package directories use singular nouns: `models/`, `schemas/`

### Python Code
- Classes: `PascalCase` - `UserService`, `UserSchema`
- Functions and variables: `snake_case` - `get_user`, `user_id`
- Constants: `UPPER_SNAKE_CASE` - `MAX_CONNECTIONS`, `API_PREFIX`
- Private members: `_leading_underscore` - `_validate_input()`

### Database Tables
- Use lowercase with underscores: `users`, `blog_posts`
- Use singular nouns: `user` (not `users`) - optional, choose one and be consistent
- Foreign key suffix: `_id` - `user_id`, `post_id`

### API Endpoints
- Use lowercase with hyphens: `/api/v1/users/`, `/blog-posts/`
- Use plural nouns for collections: `/users/`, `/posts/`
- Use singular for specific items: `/users/{user_id}/`

### Pydantic Schemas
- Base models: `{Model}Base` - `UserBase`
- Create models: `{Model}Create` - `UserCreate`
- Update models: `{Model}Update` - `UserUpdate`
- Response models: `{Model}Response` - `UserResponse`
- List models: `{Model}ListResponse` - `UserListResponse`

## Dependencies and Configuration

### Main Dependencies
- `fastapi` - Web framework
- `uvicorn[standard]` - ASGI server
- `pydantic` - Data validation
- `pydantic-settings` - Settings management
- `sqlalchemy` - ORM
- `alembic` - Database migrations
- `python-jose[cryptography]` - JWT handling
- `passlib[bcrypt]` - Password hashing
- `python-multipart` - Form data handling
- `python-dotenv` - Environment variables

### Development Dependencies
- `pytest` - Testing framework
- `pytest-asyncio` - Async test support
- `httpx` - HTTP client for testing
- `black` - Code formatter
- `ruff` - Linter
- `mypy` - Type checker

### Configuration Management

Use Pydantic Settings for configuration:

```python
from pydantic_settings import BaseSettings
from typing import Optional

class Settings(BaseSettings):
    PROJECT_NAME: str = "My API"
    VERSION: str = "1.0.0"
    API_V1_PREFIX: str = "/api/v1"
    
    DATABASE_URL: str
    SECRET_KEY: str
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    
    class Config:
        env_file = ".env"

settings = Settings()
```

## Database Patterns

### SQLAlchemy Base Model

```python
from sqlalchemy import Column, Integer, DateTime
from datetime import datetime
from app.db.base import Base

class TimestampMixin:
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)

class BaseModel(Base, TimestampMixin):
    __abstract__ = True
    id = Column(Integer, primary_key=True, index=True)
```

### CRUD Base Class

```python
from typing import TypeVar, Generic, Type, Optional, List
from pydantic import BaseModel
from sqlalchemy.orm import Session

ModelType = TypeVar("ModelType", bound=Base)
CreateSchemaType = TypeVar("CreateSchemaType", bound=BaseModel)
UpdateSchemaType = TypeVar("UpdateSchemaType", bound=BaseModel)

class CRUDBase(Generic[ModelType, CreateSchemaType, UpdateSchemaType]):
    def __init__(self, model: Type[ModelType]):
        self.model = model

    def get(self, db: Session, id: int) -> Optional[ModelType]:
        return db.query(self.model).filter(self.model.id == id).first()

    def get_multi(self, db: Session, skip: int = 0, limit: int = 100) -> List[ModelType]:
        return db.query(self.model).offset(skip).limit(limit).all()

    def create(self, db: Session, obj_in: CreateSchemaType) -> ModelType:
        obj_in_data = obj_in.dict()
        db_obj = self.model(**obj_in_data)
        db.add(db_obj)
        db.commit()
        db.refresh(db_obj)
        return db_obj

    def update(self, db: Session, db_obj: ModelType, obj_in: UpdateSchemaType) -> ModelType:
        obj_data = obj_in.dict(exclude_unset=True)
        for field, value in obj_data.items():
            setattr(db_obj, field, value)
        db.add(db_obj)
        db.commit()
        db.refresh(db_obj)
        return db_obj

    def remove(self, db: Session, id: int) -> ModelType:
        obj = db.query(self.model).get(id)
        db.delete(obj)
        db.commit()
        return obj
```

## API Design Patterns

### Response Format

Use consistent response format:

```python
from typing import Generic, TypeVar, Optional
from pydantic import BaseModel

T = TypeVar("T")

class ApiResponse(BaseModel, Generic[T]):
    success: bool
    data: Optional[T] = None
    message: Optional[str] = None
    error: Optional[str] = None

class PaginatedResponse(BaseModel, Generic[T]):
    success: bool = True
    data: List[T]
    total: int
    page: int
    page_size: int
    total_pages: int
```

### Error Handling

```python
from fastapi import HTTPException, status

class NotFoundException(HTTPException):
    def __init__(self, detail: str = "Resource not found"):
        super().__init__(status_code=status.HTTP_404_NOT_FOUND, detail=detail)

class BadRequestException(HTTPException):
    def __init__(self, detail: str = "Bad request"):
        super().__init__(status_code=status.HTTP_400_BAD_REQUEST, detail=detail)

class UnauthorizedException(HTTPException):
    def __init__(self, detail: str = "Unauthorized"):
        super().__init__(status_code=status.HTTP_401_UNAUTHORIZED, detail=detail)

class ForbiddenException(HTTPException):
    def __init__(self, detail: str = "Forbidden"):
        super().__init__(status_code=status.HTTP_403_FORBIDDEN, detail=detail)
```

### Endpoint Structure

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List
from app.api.deps import get_db
from app.schemas.user import User, UserCreate, UserUpdate
from app.crud.crud_user import crud_user

router = APIRouter()

@router.get("/", response_model=List[User])
def read_users(
    db: Session = Depends(get_db),
    skip: int = 0,
    limit: int = 100,
) -> List[User]:
    users = crud_user.get_multi(db, skip=skip, limit=limit)
    return users

@router.post("/", response_model=User)
def create_user(
    user_in: UserCreate,
    db: Session = Depends(get_db),
) -> User:
    user = crud_user.create(db, obj_in=user_in)
    return user

@router.get("/{user_id}", response_model=User)
def read_user(
    user_id: int,
    db: Session = Depends(get_db),
) -> User:
    user = crud_user.get(db, id=user_id)
    if not user:
        raise NotFoundException(f"User with id {user_id} not found")
    return user

@router.put("/{user_id}", response_model=User)
def update_user(
    user_id: int,
    user_in: UserUpdate,
    db: Session = Depends(get_db),
) -> User:
    user = crud_user.get(db, id=user_id)
    if not user:
        raise NotFoundException(f"User with id {user_id} not found")
    user = crud_user.update(db, db_obj=user, obj_in=user_in)
    return user

@router.delete("/{user_id}")
def delete_user(
    user_id: int,
    db: Session = Depends(get_db),
) -> dict:
    user = crud_user.remove(db, id=user_id)
    if not user:
        raise NotFoundException(f"User with id {user_id} not found")
    return {"message": f"User {user_id} deleted successfully"}
```

## Authentication and Authorization

### JWT Token Management

```python
from datetime import datetime, timedelta
from jose import jwt
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)

def get_password_hash(password: str) -> str:
    return pwd_context.hash(password)

def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(minutes=15)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)
    return encoded_jwt

def decode_access_token(token: str) -> dict:
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
        return payload
    except jwt.JWTError:
        raise UnauthorizedException("Could not validate credentials")
```

### Dependency Injection for Authentication

```python
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.orm import Session
from app.core.config import settings
from app.core.security import decode_access_token
from app.db.session import get_db
from app.models.user import User

oauth2_scheme = OAuth2PasswordBearer(tokenUrl=f"{settings.API_V1_PREFIX}/auth/login")

async def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> User:
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = decode_access_token(token)
        user_id: int = payload.get("sub")
        if user_id is None:
            raise credentials_exception
    except Exception:
        raise credentials_exception
    
    user = db.query(User).filter(User.id == user_id).first()
    if user is None:
        raise credentials_exception
    return user
```

## Testing Best Practices

### Test Structure

```python
import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session
from app.main import app
from app.db.session import get_db
from app.models.user import User

client = TestClient(app)

@pytest.fixture
def db_session():
    # Create test database session
    yield

def test_create_user(db_session):
    response = client.post(
        "/api/v1/users/",
        json={"email": "test@example.com", "password": "test123"}
    )
    assert response.status_code == 200
    data = response.json()
    assert data["email"] == "test@example.com"
    assert "id" in data

def test_read_user(db_session):
    # Create user first
    create_response = client.post(
        "/api/v1/users/",
        json={"email": "test@example.com", "password": "test123"}
    )
    user_id = create_response.json()["id"]
    
    # Read user
    response = client.get(f"/api/v1/users/{user_id}")
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == user_id
```

## Logging and Monitoring

### Structured Logging

```python
import logging
from app.core.config import settings

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL.upper()),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)

logger = logging.getLogger(__name__)

# Usage
logger.info("User created", extra={"user_id": user.id})
logger.error("Database connection failed", exc_info=True)
```

## Performance Optimization

### Database Query Optimization

- Use indexes on frequently queried fields
- Use `select_related` and `joinedload` for eager loading
- Avoid N+1 queries
- Use pagination for list endpoints
- Cache frequently accessed data

### Async Best Practices

- Use async/await for I/O operations
- Use `asyncio.gather` for concurrent operations
- Use connection pooling for database
- Use async HTTP clients

## Deployment Considerations

### Environment Variables

Required environment variables:
- `DATABASE_URL` - Database connection string
- `SECRET_KEY` - JWT secret key
- `ENVIRONMENT` - Development/Staging/Production
- `LOG_LEVEL` - Logging level

### Docker Configuration

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

```yaml
version: '3.8'

services:
  api:
    build: .
    ports:
      - "8000:8000"
    environment:
      - DATABASE_URL=postgresql://user:password@db:5432/dbname
    depends_on:
      - db
  
  db:
    image: postgres:15
    environment:
      - POSTGRES_USER=user
      - POSTGRES_PASSWORD=password
      - POSTGRES_DB=dbname
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```
