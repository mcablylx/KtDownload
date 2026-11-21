# 将 PNG 转换为 ICO 格式
# 需要安装 Pillow: pip install Pillow

from PIL import Image
import os

# 读取 PNG 文件
png_path = "composeApp/src/jvmMain/composeResources/drawable/img_log.png"
ico_path = "composeApp/src/jvmMain/composeResources/drawable/icon.ico"

# 打开图片
img = Image.open(png_path)

# 转换为 ICO 格式，支持多种尺寸
img.save(ico_path, format='ICO', sizes=[(16,16), (32,32), (48,48), (64,64), (128,128), (256,256)])

print(f"成功创建图标文件: {ico_path}")
