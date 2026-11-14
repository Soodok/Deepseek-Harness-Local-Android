// Original DSH permission artwork, Copyright (c) 2026 DeepSeek.
// SVG paths from @deepseek-ai/dsh-web-frontend permission glyphs (16×16 viewBox).
// MIT License
//
// Copyright (c) 2026 DeepSeek
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
import 'package:material_ui/material_ui.dart';

enum DshPermissionGlyph { readOnly, workspaceWrite, fullAccess }

class DshPermissionIcon extends StatelessWidget {
  const DshPermissionIcon({
    super.key,
    required this.glyph,
    this.size = 22,
    this.color,
  });
  final DshPermissionGlyph glyph;
  final double size;
  final Color? color;
  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: size,
    child: CustomPaint(
      painter: _PermissionPainter(
        glyph,
        color ?? IconTheme.of(context).color ?? Colors.white,
      ),
    ),
  );
}

class _PermissionPainter extends CustomPainter {
  const _PermissionPainter(this.glyph, this.color);
  final DshPermissionGlyph glyph;
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 16, size.height / 16);
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1
      ..strokeJoin = StrokeJoin.round;
    final fill = Paint()
      ..color = color
      ..style = PaintingStyle.fill;
    switch (glyph) {
      case DshPermissionGlyph.readOnly:
        canvas.drawPath(
          Path()
            ..moveTo(5.08545, 8.13775)
            ..lineTo(7.18455, 10.2368)
            ..cubicTo(7.26636, 10.3187, 7.4003, 10.3142, 7.47649, 10.2271)
            ..lineTo(11.5148, 5.61194),
          stroke,
        );
        canvas.drawPath(
          Path()
            ..moveTo(6.59624, 2.14853)
            ..cubicTo(7.50155, 1.80917, 8.49914, 1.80919, 9.40444, 2.14859)
            ..lineTo(13.9245, 3.84317)
            ..lineTo(13.9245, 7.11961)
            ..cubicTo(13.9245, 11.6089, 10.5565, 13.5975, 8.00035, 14.5779)
            ..cubicTo(5.44423, 13.5975, 2.07544, 11.6089, 2.07544, 7.11961)
            ..lineTo(2.07544, 3.84317)
            ..lineTo(6.59624, 2.14853)
            ..close(),
          stroke,
        );
        break;
      case DshPermissionGlyph.workspaceWrite:
        canvas.drawPath(
          Path()
            ..moveTo(6.4209, 1.68067)
            ..cubicTo(7.43922, 1.299, 8.56177, 1.29898, 9.58008, 1.68067)
            ..lineTo(14.0996, 3.375)
            ..cubicTo(14.2946, 3.44811, 14.4236, 3.63455, 14.4238, 3.84278)
            ..lineTo(14.4238, 6.89063)
            ..cubicTo(14.1115, 6.71853, 13.7761, 6.58312, 13.4238, 6.48926)
            ..lineTo(13.4238, 4.18946)
            ..lineTo(9.22852, 2.61621)
            ..cubicTo(8.43657, 2.31947, 7.56341, 2.31939, 6.77148, 2.61621)
            ..lineTo(2.5752, 4.18946)
            ..lineTo(2.5752, 7.11914)
            ..cubicTo(2.5752, 11.1796, 5.52369, 13.056, 8, 14.0391)
            ..cubicTo(8.27653, 13.9293, 8.55827, 13.8067, 8.8418, 13.6729)
            ..cubicTo(9.07101, 13.9468, 9.33228, 14.1929, 9.62012, 14.4053)
            ..cubicTo(9.12409, 14.6579, 8.63578, 14.8696, 8.17871, 15.0449)
            ..cubicTo(8.0637, 15.0889, 7.93628, 15.0889, 7.82129, 15.0449)
            ..cubicTo(5.22011, 14.0472, 1.5752, 11.9381, 1.5752, 7.11914)
            ..lineTo(1.5752, 3.84278)
            ..cubicTo(1.57541, 3.63469, 1.70463, 3.44821, 1.89941, 3.375)
            ..lineTo(6.4209, 1.68067)
            ..close(),
          fill,
        );
        canvas.drawPath(
          Path()
            ..moveTo(5.26392, 6.60339)
            ..lineTo(10.7361, 6.60339),
          stroke,
        );
        canvas.drawPath(
          Path()
            ..moveTo(5.26392, 9.86902)
            ..lineTo(8.32833, 9.86902),
          stroke,
        );
        canvas.drawPath(
          Path()
            ..moveTo(10.0317, 13.2229)
            ..cubicTo(10.263, 13.3929, 10.4943, 13.563, 10.7256, 13.733)
            ..cubicTo(10.7932, 13.6482, 10.8608, 13.5634, 10.9284, 13.4786)
            ..cubicTo(12.1455, 11.9522, 13.3626, 10.4258, 14.5798, 8.89935)
            ..cubicTo(14.6474, 8.81455, 14.715, 8.72975, 14.7826, 8.64495)
            ..cubicTo(14.4143, 8.37419, 14.046, 8.10344, 13.6777, 7.83268)
            ..cubicTo(13.6169, 7.92252, 13.5562, 8.01236, 13.4954, 8.10219)
            ..cubicTo(12.4016, 9.71926, 11.3078, 11.3363, 10.214, 12.9534)
            ..cubicTo(10.1532, 13.0432, 10.0924, 13.1331, 10.0317, 13.2229)
            ..close(),
          fill,
        );
        canvas.drawPath(
          Path()
            ..moveTo(12.6516, 12.6696)
            ..cubicTo(12.6516, 12.925, 12.6516, 13.1804, 12.6516, 13.4359)
            ..cubicTo(12.6952, 13.4378, 12.7387, 13.4398, 12.7823, 13.4417)
            ..cubicTo(13.5663, 13.4768, 14.3504, 13.5118, 15.1345, 13.5469)
            ..cubicTo(15.178, 13.5488, 15.2216, 13.5508, 15.2651, 13.5527)
            ..cubicTo(15.2651, 13.2194, 15.2651, 12.8861, 15.2651, 12.5527)
            ..cubicTo(15.2216, 12.5547, 15.178, 12.5566, 15.1345, 12.5586)
            ..cubicTo(14.3504, 12.5936, 13.5663, 12.6287, 12.7823, 12.6637)
            ..cubicTo(12.7387, 12.6657, 12.6952, 12.6676, 12.6516, 12.6696)
            ..close(),
          fill,
        );
        break;
      case DshPermissionGlyph.fullAccess:
        canvas.drawPath(
          Path()
            ..moveTo(6.59624, 2.14853)
            ..cubicTo(7.50155, 1.80917, 8.49914, 1.80919, 9.40444, 2.14859)
            ..lineTo(13.9245, 3.84317)
            ..lineTo(13.9245, 7.11961)
            ..cubicTo(13.9245, 11.6089, 10.5565, 13.5975, 8.00035, 14.5779)
            ..cubicTo(5.44423, 13.5975, 2.07544, 11.6089, 2.07544, 7.11961)
            ..lineTo(2.07544, 3.84317)
            ..lineTo(6.59624, 2.14853)
            ..close(),
          stroke,
        );
        canvas.drawPath(
          Path()
            ..moveTo(8, 4.39209)
            ..lineTo(8, 9.89209),
          stroke,
        );
        canvas.drawPath(
          Path()
            ..moveTo(8, 10.8081)
            ..lineTo(8, 11.8081),
          stroke,
        );
        break;
    }
  }

  @override
  bool shouldRepaint(covariant _PermissionPainter old) =>
      old.glyph != glyph || old.color != color;
}
