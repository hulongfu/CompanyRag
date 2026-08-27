"""
Pydantic model templates for data validation.
"""
from pydantic import BaseModel, EmailStr, Field, validator, field_validator
from typing import Optional, List
from datetime import datetime


# Base model with common fields
class TimestampMixin(BaseModel):
    """Mixin for timestamp fields."""
    created_at: datetime
    updated_at: datetime
    
    class Config:
        from_attributes = True


# User-related schemas
class UserBase(BaseModel):
    """Base user schema."""
    email: EmailStr
    first_name: Optional[str] = Field(None, max_length=100)
    last_name: Optional[str] = Field(None, max_length=100)


class UserCreate(UserBase):
    """Schema for creating a user."""
    email: EmailStr
    password: str = Field(..., min_length=8, max_length=100)
    first_name: Optional[str] = Field(None, max_length=100)
    last_name: Optional[str] = Field(None, max_length=100)
    
    @field_validator('password')
    @classmethod
    def validate_password(cls, v):
        """Validate password strength."""
        if len(v) < 8:
            raise ValueError('Password must be at least 8 characters')
        if not any(char.isupper() for char in v):
            raise ValueError('Password must contain at least one uppercase letter')
        if not any(char.islower() for char in v):
            raise ValueError('Password must contain at least one lowercase letter')
        if not any(char.isdigit() for char in v):
            raise ValueError('Password must contain at least one digit')
        return v


class UserUpdate(BaseModel):
    """Schema for updating a user."""
    first_name: Optional[str] = Field(None, max_length=100)
    last_name: Optional[str] = Field(None, max_length=100)
    password: Optional[str] = Field(None, min_length=8, max_length=100)


class User(UserBase, TimestampMixin):
    """User response schema."""
    id: int
    is_active: bool = True
    is_verified: bool = False


class UserListResponse(BaseModel):
    """Paginated user list response."""
    success: bool = True
    data: List[User]
    total: int
    page: int
    page_size: int
    total_pages: int = Field(alias="total_pages")


# Post-related schemas
class PostBase(BaseModel):
    """Base post schema."""
    title: str = Field(..., min_length=1, max_length=255)
    content: Optional[str] = None
    excerpt: Optional[str] = Field(None, max_length=500)


class PostCreate(PostBase):
    """Schema for creating a post."""
    title: str
    content: str
    user_id: int
    
    @field_validator('title')
    @classmethod
    def validate_title(cls, v):
        """Validate title doesn't contain profanity."""
        # Add your validation logic here
        return v


class PostUpdate(BaseModel):
    """Schema for updating a post."""
    title: Optional[str] = Field(None, min_length=1, max_length=255)
    content: Optional[str] = None
    excerpt: Optional[str] = Field(None, max_length=500)


class Post(PostBase, TimestampMixin):
    """Post response schema."""
    id: int
    user_id: int
    status: str = "draft"
    view_count: int = 0


class PostListResponse(BaseModel):
    """Paginated post list response."""
    success: bool = True
    data: List[Post]
    total: int
    page: int
    page_size: int
    total_pages: int = Field(alias="total_pages")


# Comment schemas
class CommentBase(BaseModel):
    """Base comment schema."""
    content: str = Field(..., min_length=1, max_length=2000)


class CommentCreate(CommentBase):
    """Schema for creating a comment."""
    content: str
    post_id: int
    user_id: Optional[int] = None


class CommentUpdate(BaseModel):
    """Schema for updating a comment."""
    content: Optional[str] = Field(None, min_length=1, max_length=2000)


class Comment(CommentBase, TimestampMixin):
    """Comment response schema."""
    id: int
    post_id: int
    user_id: Optional[int] = None
    parent_id: Optional[int] = None
    is_approved: bool = False


class CommentListResponse(BaseModel):
    """Paginated comment list response."""
    success: bool = True
    data: List[Comment]
    total: int
    page: int
    page_size: int
    total_pages: int = Field(alias="total_pages")


# Generic response wrapper
class ApiResponse(BaseModel, Generic[T]):
    """Generic API response."""
    success: bool = True
    data: Optional[T] = None
    message: Optional[str] = None


# Error response
class ErrorResponse(BaseModel):
    """Error response schema."""
    success: bool = False
    error: dict
    message: Optional[str] = None


# Login schemas
class LoginRequest(BaseModel):
    """Login request schema."""
    email: EmailStr
    password: str


class LoginResponse(BaseModel):
    """Login response schema."""
    success: bool = True
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    user: User


# Filter and pagination schemas
class PaginationParams(BaseModel):
    """Pagination parameters."""
    page: int = Field(1, ge=1)
    page_size: int = Field(20, ge=1, le=100)


class OrderByField(BaseModel):
    """Order by field specification."""
    field: str
    direction: str = Field("asc", pattern="^(asc|desc)$")


class FilterParams(BaseModel):
    """Base filter parameters."""
    search: Optional[str] = None
    order_by: Optional[List[OrderByField]] = None
    
    class Config:
        extra = "allow"
