"""
Base model with common fields.
"""
from sqlalchemy import Column, Integer, DateTime, Boolean
from datetime import datetime


class BaseModel:
    """Base model with common fields."""
    
    id = Column(Integer, primary_key=True, index=True)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)
    is_active = Column(Boolean, default=True)
    deleted_at = Column(DateTime, nullable=True)
