import base64
import binascii
import json
from io import BytesIO
from threading import BoundedSemaphore
from typing import Literal
from urllib.error import URLError
from urllib.request import Request, urlopen

from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, ValidationError

MODEL = 'inspection-qwen'
URL = 'http://127.0.0.1:8081/v1/chat/completions'
SLOTS = BoundedSemaphore()


class VisionFrame(BaseModel):
    jpeg_base64: str = Field(min_length=32, max_length=700_000)
    scope: Literal['frame', 'candidate'] = 'frame'


class VisionReview(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    status: Literal['visible_sign', 'no_visible_sign', 'insufficient_view']
    observation: str = Field(min_length=1, max_length=220)


class VisionBusy(Exception):
    pass


class VisionUnavailable(Exception):
    pass


def verify_jpeg(encoded):
    try:
        data = base64.b64decode(encoded, validate=True)
        with Image.open(BytesIO(data), formats=['JPEG']) as image:
            if not all(1 <= side <= 1024 for side in image.size):
                raise ValueError('Неверный размер кадра.')
            image.load()
            image.thumbnail((512, 512), Image.Resampling.LANCZOS)
            scaled = BytesIO()
            image.convert('RGB').save(scaled, 'JPEG', quality=86)
    except (binascii.Error, UnidentifiedImageError, Image.DecompressionBombError, OSError, SyntaxError) as error:
        raise ValueError('Нужен корректный кадр JPEG.') from error
    return base64.b64encode(scaled.getvalue()).decode('ascii')


def inspect_frame(frame, check):
    jpeg = verify_jpeg(frame.jpeg_base64)
    if not SLOTS.acquire(blocking=False):
        raise VisionBusy()
    try:
        message = (
            f"Проверка: {check['title']}. Критерий: {check['acceptance_criteria']}. "
            f"Изображение: {'область кандидата' if frame.scope == 'candidate' else 'кадр камеры'}. "
            "Опиши видимый признак одной короткой фразой по-русски. "
            "Статус: visible_sign, no_visible_sign или insufficient_view. "
            "Если детали не видно, выбери insufficient_view. Не утверждай причину, исправность или способ ремонта."
        )
        payload = {
            'model': MODEL, 'temperature': 0, 'max_tokens': 120,
            'response_format': {'type': 'json_schema', 'json_schema': {
                'name': 'inspection_review', 'strict': True, 'schema': VisionReview.model_json_schema(),
            }},
            'messages': [{'role': 'user', 'content': [
                {'type': 'text', 'text': message},
                {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,' + jpeg}},
            ]}],
        }
        request = Request(URL, json.dumps(payload).encode('utf-8'), {'Content-Type': 'application/json'})
        with urlopen(request, timeout=38) as response:
            content = json.load(response)['choices'][0]['message']['content']
        return VisionReview.model_validate_json(content)
    except (URLError, TimeoutError, KeyError, IndexError, TypeError, json.JSONDecodeError, ValidationError) as error:
        raise VisionUnavailable() from error
    finally:
        SLOTS.release()
