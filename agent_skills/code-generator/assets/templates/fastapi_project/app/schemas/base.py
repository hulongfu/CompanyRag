"""
Base Pydantic schemas.
"""
from typing import Generic, TypeVar, Optional, List
from pydantic import BaseModel, Field
from datetime import datetime


T = TypeVar("T")


class BaseResponse(BaseModel):
    """Base response schema."""
    success: bool = True
    message: Optional[str] = None


class ApiResponse(BaseResponse, Generic[T]):
    """Generic API response."""
    data: Optional[T] = None


class PaginatedResponse(BaseResponse, Generic[T]):
    """Paginated response."""
    data: List[T]
    total: int
    page: int
    page_size: int
    total_pages: int = Field(alias="total_pages")


class TimestampMixin(BaseModel):
    """Mixin for timestamps."""
    created_at: datetime
    updated_at: datetime
    
    class Config:
        from_attributes = True
